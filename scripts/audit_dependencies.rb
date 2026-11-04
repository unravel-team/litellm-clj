# Query OSV for public Maven coordinates from the resolved inventories.
# This command needs network access. It is not part of the offline unit suite.
require 'csv'
require 'json'
require 'net/http'
require 'time'

abort 'Usage: ruby scripts/audit_dependencies.rb INVENTORY.tsv ...' if ARGV.empty?
packages = ARGV.flat_map do |path|
  CSV.read(path, headers: true, col_sep: "\t").map do |row|
    { 'package' => { 'ecosystem' => 'Maven', 'name' => row.fetch('coordinate').tr('/', ':') },
      'version' => row.fetch('version') }
  end
end.uniq.sort_by { |package| [package.fetch('package').fetch('name'), package.fetch('version')] }

findings = packages.each_slice(100).flat_map do |batch|
  uri = URI('https://api.osv.dev/v1/querybatch')
  request = Net::HTTP::Post.new(uri, 'Content-Type' => 'application/json')
  request.body = JSON.generate('queries' => batch)
  response = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 15, read_timeout: 60) do |http|
    http.request(request)
  end
  abort "OSV request failed: #{response.code}" unless response.is_a?(Net::HTTPSuccess)
  results = JSON.parse(response.body).fetch('results')
  abort 'OSV result count mismatch' unless results.length == batch.length
  batch.zip(results).map { |package, result| package.merge(result) }
end
report = { 'queried_at' => Time.now.utc.iso8601, 'source' => 'https://api.osv.dev', 'packages' => findings }
File.write('target/security/osv.json', JSON.pretty_generate(report) + "\n")
vulnerable = findings.select { |finding| !finding.fetch('vulns', []).empty? }
vulnerable.each do |finding|
  puts "#{finding.fetch('package').fetch('name')} #{finding.fetch('version')}: #{finding.fetch('vulns').map { |v| v.fetch('id') }.join(', ')}"
end
puts "Queried #{findings.length} package versions; #{vulnerable.length} with advisory matches. Report: target/security/osv.json"
exit(vulnerable.empty? ? 0 : 1)
