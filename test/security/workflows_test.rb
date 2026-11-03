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
      next if path.end_with?('/e2e.yml')
      refute_includes workflow.reject { |key, _| key == 'jobs' }.to_s, 'secrets.'
      workflow.fetch('jobs').each_value do |job|
        refute_includes job.reject { |key, _| key == 'steps' }.to_s, 'secrets.'
        job.fetch('steps').each do |step|
          next unless step.to_s.include?('secrets.')
          assert step.fetch('uses', '').start_with?('codecov/codecov-action@')
          assert_equal "matrix.java == '17' && github.event_name == 'push' && github.ref == 'refs/heads/main'", step.fetch('if', nil)
          assert_equal '${{ secrets.CODECOV_TOKEN }}', step.fetch('with', {}).fetch('token', nil)
          refute_includes step.reject { |key, _| key == 'with' }.to_s, 'secrets.'
          refute_includes step.fetch('with').reject { |key, _| key == 'token' }.to_s, 'secrets.'
        end
      end
    end
  end

  def test_ci_does_not_restore_dependency_caches
    workflows.each_value do |workflow|
      workflow.fetch('jobs').each_value do |job|
        job.fetch('steps').each do |step|
          refute_match(/actions\/cache(?:\/|@)/, step.fetch('uses', ''))
          refute step.fetch('with', {}).key?('cache'), 'Setup actions must not restore dependency caches'
        end
      end
    end
  end

  def test_codecov_service_remains_available_only_after_main_push
    steps = workflows.fetch('.github/workflows/test.yml').fetch('jobs').fetch('test').fetch('steps')
    upload = steps.find { |step| step.fetch('uses', '').start_with?('codecov/codecov-action@') }
    refute_nil upload
    assert_equal "matrix.java == '17' && github.event_name == 'push' && github.ref == 'refs/heads/main'", upload.fetch('if')
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
