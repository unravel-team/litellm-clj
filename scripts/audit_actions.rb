# Read-only audit of pinned action manifests and runtime npm dependencies.
# Upstream lockfiles stay in memory. This script never creates or changes a lockfile.
require 'digest'
require 'fileutils'
require 'json'
require 'net/http'
require 'time'
require 'yaml'

OUTPUT = 'target/security/actions.json'
FileUtils.rm_f(OUTPUT)

def fetch_json_or_text(url)
  uri = URI(url)
  response = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 15, read_timeout: 60) do |http|
    http.get(uri.request_uri)
  end
  abort "Public source read failed: #{url} (#{response.code})" unless response.is_a?(Net::HTTPSuccess)
  response.body
end

references = Dir['.github/workflows/*.{yml,yaml}'].flat_map do |path|
  YAML.load_file(path).fetch('jobs').values.flat_map do |job|
    job.fetch('steps').map { |step| step['uses'] }.compact
  end
end.uniq.sort

actions = references.map do |reference|
  repository, revision = reference.split('@', 2)
  abort "Action is not pinned: #{reference}" unless revision && revision.match?(/\A[0-9a-f]{40}\z/)
  base = "https://raw.githubusercontent.com/#{repository}/#{revision}"
  manifest_text = fetch_json_or_text("#{base}/action.yml")
  manifest = YAML.safe_load(manifest_text)
  runtime = manifest.fetch('runs').fetch('using')
  abort "Composite and Docker actions need a separate audit: #{reference}" unless runtime.start_with?('node')
  lock_text = fetch_json_or_text("#{base}/package-lock.json")
  lock = JSON.parse(lock_text)
  packages = lock.fetch('packages').map do |path, info|
    next if path.empty? || info['dev'] || !info['version']
    name = info['name'] || path.split('node_modules/').last
    { 'package' => { 'ecosystem' => 'npm', 'name' => name }, 'version' => info.fetch('version') }
  end.compact.uniq.sort_by { |package| [package.fetch('package').fetch('name'), package.fetch('version')] }
  entry_points = manifest.fetch('runs').values_at('main', 'post', 'pre').compact.uniq
  bundles = entry_points.to_h do |path|
    bytes = fetch_json_or_text("#{base}/#{path}")
    [path, { 'sha256' => Digest::SHA256.hexdigest(bytes), 'bytes' => bytes.bytesize }]
  end
  { 'reference' => reference, 'runtime' => runtime, 'manifest_sha256' => Digest::SHA256.hexdigest(manifest_text),
    'upstream_lock_sha256' => Digest::SHA256.hexdigest(lock_text), 'bundles' => bundles,
    'runtime_packages' => packages }
end
packages = actions.flat_map { |action| action.fetch('runtime_packages') }.uniq
results = packages.each_slice(100).flat_map do |batch|
  uri = URI('https://api.osv.dev/v1/querybatch')
  request = Net::HTTP::Post.new(uri, 'Content-Type' => 'application/json')
  request.body = JSON.generate('queries' => batch)
  response = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 15, read_timeout: 60) do |http|
    http.request(request)
  end
  abort "OSV failed: #{response.code}" unless response.is_a?(Net::HTTPSuccess)
  entries = JSON.parse(response.body).fetch('results')
  abort 'OSV result count mismatch' unless entries.length == batch.length
  batch.zip(entries).map { |package, result| package.merge(result) }
end
report = { 'queried_at' => Time.now.utc.iso8601, 'source' => 'https://api.osv.dev',
           'actions' => actions, 'packages' => results }
FileUtils.mkdir_p(File.dirname(OUTPUT))
File.write(OUTPUT, JSON.pretty_generate(report) + "\n")
affected = results.select { |entry| !entry.fetch('vulns', []).empty? }
affected.each do |entry|
  puts "#{entry.fetch('package').fetch('name')} #{entry.fetch('version')}: #{entry.fetch('vulns').map { |v| v.fetch('id') }.join(', ')}"
end
puts "Audited #{actions.length} pinned actions, #{packages.length} runtime npm package versions; #{affected.length} advisory matches. #{OUTPUT}"
exit(affected.empty? ? 0 : 1)
