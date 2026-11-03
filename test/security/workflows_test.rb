require 'minitest/autorun'
require 'yaml'

class WorkflowSecurityTest < Minitest::Test
  def workflows
    Dir['.github/workflows/*.{yml,yaml}'].to_h do |path|
      [path, YAML.load_file(path)]
    end
  end

  def test_actions_are_pinned_and_tokens_are_read_only
    workflows.each do |path, workflow|
      assert_equal({'contents' => 'read'}, workflow.fetch('permissions', nil), path)
      workflow.fetch('jobs').each_value do |job|
        job.fetch('steps').each do |step|
          next unless step.key?('uses')
          assert_match(/\A[^@]+@[0-9a-f]{40}\z/, step.fetch('uses'), path)
          if step.fetch('uses').start_with?('actions/checkout@')
            assert_equal false, step.fetch('with', {}).fetch('persist-credentials', nil), path
          end
        end
      end
      unless path.end_with?('/e2e.yml')
        refute_includes File.read(path), 'secrets.', 'PR checks must not receive credentials'
      end
    end
  end

  def test_provider_credentials_only_reach_approved_manual_main_runs
    workflow = workflows.fetch('.github/workflows/e2e.yml')
    # YAML 1.1 treats the unquoted GitHub Actions key `on` as true.
    triggers = workflow.fetch('on') { workflow.fetch(true) }
    assert_equal ['workflow_dispatch'], triggers.keys
    job = workflow.fetch('jobs').fetch('e2e-test')
    assert_equal "github.ref == 'refs/heads/main'", job.fetch('if', nil)
    assert_equal 'provider-e2e', job.fetch('environment', nil)
    assert_equal 'main', job.fetch('steps').first.fetch('with', {}).fetch('ref', nil)
  end
end
