# macOS-only disposable verification. Run make dependency-inventory first.
require 'csv'
require 'digest'
require 'fileutils'
require 'json'
require 'open3'
require 'set'
require 'tmpdir'
require 'time'

abort 'This verification requires macOS sandbox-exec' unless RUBY_PLATFORM.include?('darwin')
root = Dir.pwd
base = File.join(root, '.verification')
FileUtils.mkdir_p(base)
work = Dir.mktmpdir('run-', base)
source = File.join(work, 'source')
home = File.join(work, 'home')
FileUtils.mkdir_p([source, File.join(home, '.m2', 'repository'), File.join(work, 'tmp')])

# jj has no archive command. Git archive exports the already-reviewed Git HEAD without a checkout or branch change.
archive = File.join(work, 'source.tar')
abort 'Source archive failed' unless system('git', 'archive', '--format=tar', '-o', archive, 'HEAD')
abort 'Source extraction failed' unless system('tar', '-xf', archive, '-C', source)
# Copy local history needed for version/revision metadata, but no hooks, credentials, or Git configuration.
git_dir = File.join(source, '.git')
FileUtils.mkdir_p(git_dir)
%w[objects refs HEAD packed-refs index shallow].each do |entry|
  from = File.join(root, '.git', entry)
  FileUtils.cp_r(from, git_dir) if File.exist?(from)
end
File.write(File.join(git_dir, 'config'), "[core]\nrepositoryformatversion = 0\nbare = false\n")
inventories = Dir[File.join(root, 'target/security/*.tsv')]
abort 'Run make dependency-inventory first' if inventories.empty?

sentinel = File.join(base, 'nonsecret-sentinel')
File.write(sentinel, 'nonsecret sandbox read/write probe')
runner = File.join(work, 'run.sh')
File.write(runner, <<~SH)
  set -eu
  cd "$WORK/source"
  clojure -Srepro -M scripts/sandbox_probe.clj "$SENTINEL"
  make lint
  make test
  make test-release
  make coverage
  clojure -Srepro -M:test:bedrock scripts/verify_bedrock.clj
  make dependency-inventory
  ruby test/security/workflows_test.rb
SH
java_home, java_status = Open3.capture2('/usr/libexec/java_home')
abort 'Cannot select JDK' unless java_status.success?
environment = {
  'HOME' => home, 'PATH' => '/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin',
  'JAVA_HOME' => java_home.strip, 'CLJ_CONFIG' => File.join(home, '.clojure'),
  'TMPDIR' => File.join(work, 'tmp'), 'WORK' => work, 'SENTINEL' => sentinel,
  'JAVA_TOOL_OPTIONS' => "-Duser.home=#{home} -Djava.io.tmpdir=#{work}/tmp",
  'AWS_EC2_METADATA_DISABLED' => 'true', 'LANG' => 'en_US.UTF-8'
}
# Networked preparation uses only the declared public Maven repositories, an empty
# home, and the same cleared environment. -P resolves artifacts without running
# their main entry points. Never copy personal Maven settings or unrelated POMs.
prepare_log = File.join(work, 'preparation.log')
['', ':test', ':test:coverage', ':build', ':bedrock', ':outdated', ':kondo'].each do |profile|
  prepared = system(environment, 'clojure', '-Srepro', '-P', "-M#{profile}",
                    unsetenv_others: true, chdir: source, out: [prepare_log, 'a'], err: [:child, :out])
  abort "Public dependency preparation failed; see #{prepare_log}" unless prepared
end
repository = File.join(home, '.m2', 'repository')
inventories.each do |inventory|
  CSV.foreach(inventory, headers: true, col_sep: "\t") do |row|
    group, artifact = row.fetch('coordinate').split('/', 2)
    artifact = artifact.split('$', 2).first
    path = File.join(repository, group.tr('.', '/'), artifact, row.fetch('version'), row.fetch('artifact'))
    abort "Artifact checksum mismatch: #{row.fetch('coordinate')}" unless Digest::SHA256.file(path).hexdigest == row.fetch('sha256')
  end
end

def file_manifest(work)
  Dir.glob(File.join(work, '**', '*'), File::FNM_DOTMATCH).select { |path| File.file?(path) }.sort.to_h do |path|
    [path.delete_prefix(work + '/'), Digest::SHA256.file(path).hexdigest]
  end
end

initial_files = file_manifest(work)
File.write(File.join(work, 'initial-files.json'), JSON.pretty_generate(initial_files) + "\n")
log = File.join(work, 'verification.log')
processes = Set.new
pid = Process.spawn(environment, '/usr/bin/sandbox-exec', '-D', "WORK=#{work}", '-f',
                    File.join(root, 'scripts/offline-verification.sb'), '/bin/sh', runner,
                    unsetenv_others: true, pgroup: true, chdir: work, out: log, err: [:child, :out])
loop do
  IO.popen(['/bin/ps', '-axo', 'pgid=,comm=']) do |output|
    output.each_line do |line|
      group, command = line.strip.split(/\s+/, 2)
      processes.add(command) if group.to_i == pid && command
    end
  end
  break if Process.waitpid(pid, Process::WNOHANG)
  sleep 0.05
end
status = $?.exitstatus
inventory_matches = inventories.to_h do |inventory|
  actual = File.join(source, 'target', 'security', File.basename(inventory))
  [File.basename(inventory), File.exist?(actual) && File.binread(inventory) == File.binread(actual)]
end
status = 1 unless inventory_matches.values.all?
File.write(File.join(work, 'observed-processes.txt'), processes.to_a.sort.join("\n") + "\n")
final_files = file_manifest(work)
File.write(File.join(work, 'final-files.json'), JSON.pretty_generate(final_files) + "\n")
File.write(File.join(work, 'file-changes.json'), JSON.pretty_generate(
  'created' => final_files.keys - initial_files.keys,
  'removed' => initial_files.keys - final_files.keys,
  'modified' => (initial_files.keys & final_files.keys).select { |path| initial_files[path] != final_files[path] }
) + "\n")
abort 'Outside-workspace sentinel changed' unless File.read(sentinel) == 'nonsecret sandbox read/write probe'
revision, revision_status = Open3.capture2('git', 'rev-parse', 'HEAD')
abort 'Cannot identify source revision' unless revision_status.success?
File.write(File.join(work, 'result.json'), JSON.pretty_generate(
  'verified_at' => Time.now.utc.iso8601, 'source_revision' => revision.strip,
  'runner_sha256' => Digest::SHA256.file(__FILE__).hexdigest,
  'profile_sha256' => Digest::SHA256.file(File.join(root, 'scripts/offline-verification.sb')).hexdigest,
  'exit_status' => status, 'inventory_matches' => inventory_matches, 'process_poll_ms' => 50,
  'observed_processes' => processes.to_a.sort, 'file_count' => final_files.length
) + "\n")
puts "Verification workspace: #{work}"
puts "Exit status: #{status}; log: #{log}"
exit(status || 1)
