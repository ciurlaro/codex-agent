use super::*;

    #[test]
    fn capability_profile_rejects_process_git_plugin_and_mcp_routes() {
        let overrides = safe_config_overrides()
            .into_iter()
            .collect::<std::collections::HashMap<_, _>>();
        for feature in [
            "features.code_mode",
            "features.code_mode_buffered_exec",
            "features.code_mode_host",
            "features.code_mode_only",
        ] {
            assert_eq!(overrides.get(feature), Some(&TomlValue::Boolean(false)));
        }
        assert_eq!(
            unsupported_client_capability("command/exec"),
            Some("process execution")
        );
        assert_eq!(
            unsupported_client_capability("thread/shellCommand"),
            Some("process execution")
        );
        assert_eq!(
            unsupported_client_capability("plugin/list"),
            Some("plugins and apps")
        );
        assert_eq!(
            unsupported_client_capability("mcpServerStatus/list"),
            Some("MCP")
        );
        assert_eq!(unsupported_client_capability("thread/start"), None);
    }

    #[test]
    fn runtime_has_no_process_environment() {
        let manager = EnvironmentManager::without_environments();
        assert!(manager.default_environment().is_none());
        assert!(manager.try_local_environment().is_none());
    }

    #[test]
    fn thread_start_is_confined_and_filters_dynamic_tools() {
        let (_sandbox, workspace) = workspace();
        let mut request = json!({
            "id": 1,
            "method": "thread/start",
            "params": {
                "cwd": "/outside",
                "sandbox": "danger-full-access",
                "developerInstructions": "use shell",
                "config": { "features": { "shell_tool": true } },
                "dynamicTools": [
                    { "name": "apply_patch" },
                    { "name": "read_file" },
                    { "name": "run_command" }
                ]
            }
        });
        sanitize_request(&mut request, "thread/start", &workspace).expect("sanitize");
        let params = request["params"].as_object().unwrap();
        assert_eq!(
            params["cwd"],
            Value::String(workspace.to_string_lossy().into_owned())
        );
        assert_eq!(
            params["runtimeWorkspaceRoots"],
            json!([workspace.to_string_lossy()])
        );
        assert_eq!(params["sandbox"], "workspace-write");
        assert_eq!(params["config"]["features"]["shell_tool"], false);
        assert_eq!(params["config"]["features"]["code_mode"], false);
        assert_eq!(
            params["config"]["features"]["code_mode_buffered_exec"],
            false
        );
        assert_eq!(params["config"]["features"]["code_mode_host"], false);
        assert_eq!(params["config"]["features"]["code_mode_only"], false);
        assert_eq!(params["dynamicTools"].as_array().unwrap().len(), 2);
        assert_eq!(params["dynamicTools"][0]["name"], "apply_patch");
        assert_eq!(params["dynamicTools"][1]["name"], "read_file");
    }

    #[test]
    fn turn_start_is_confined_to_workspace_write() {
        let (_sandbox, workspace) = workspace();
        let mut request = json!({
            "id": 2,
            "method": "turn/start",
            "params": {
                "threadId": "thread",
                "input": [],
                "cwd": "/outside",
                "sandboxPolicy": { "type": "dangerFullAccess" },
                "permissions": "unrestricted"
            }
        });

        sanitize_request(&mut request, "turn/start", &workspace).expect("sanitize");
        let params = request["params"].as_object().unwrap();
        assert_eq!(
            params["cwd"],
            Value::String(workspace.to_string_lossy().into_owned())
        );
        assert_eq!(params["sandboxPolicy"]["type"], "workspaceWrite");
        assert_eq!(params["sandboxPolicy"]["networkAccess"], false);
        assert!(!params.contains_key("permissions"));
    }
