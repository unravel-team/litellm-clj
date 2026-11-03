require 'minitest/autorun'
require 'open3'
require 'rexml/document'

class ReleaseProvenanceTest < Minitest::Test
  def read_entry(jar, entry)
    content, status = Open3.capture2('unzip', '-p', jar, entry, err: File::NULL)
    assert status.success?, "JAR must contain #{entry}"
    content
  end

  def test_artifact_identifies_canonical_source_and_revision
    jars = Dir['target/litellm-clj-*.jar']
    assert_equal 1, jars.length, 'Run make build before this test'
    pom = REXML::Document.new(read_entry(jars.first, 'META-INF/maven/tech.unravel/litellm-clj/pom.xml'))
    project = pom.root
    assert_equal 'https://github.com/unravel-team/litellm-clj', project.elements['url'].text
    scm = project.elements['scm']
    assert_equal 'https://github.com/unravel-team/litellm-clj', scm.elements['url'].text
    %w[connection developerConnection].each do |element|
      assert_equal 'scm:git:https://github.com/unravel-team/litellm-clj.git', scm.elements[element].text
    end
    revision = scm.elements['tag'].text
    assert_match(/\A[0-9a-f]{40}\z/, revision)
    info = read_entry(jars.first, 'META-INF/litellm-clj/build-info.edn')
    assert_includes info, ":revision \"#{revision}\""
    assert_match(/:dirty\? (?:true|false)/, info)
  end
end
