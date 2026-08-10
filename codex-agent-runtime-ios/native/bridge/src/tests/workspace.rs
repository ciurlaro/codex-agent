use super::*;

    #[test]
    fn local_tools_read_search_list_and_write_without_processes() {
        let (_sandbox, workspace) = workspace();
        fs::write(workspace.join("input.txt"), "alpha\nbeta\n").expect("fixture");

        let read = execute_workspace_tool(&workspace, "read_file", json!({ "path": "input.txt" }));
        assert!(read.success);
        assert_eq!(read.text, "alpha\nbeta\n");

        let search = execute_workspace_tool(&workspace, "search_text", json!({ "query": "BETA" }));
        assert!(search.success);
        assert!(search.text.contains("input.txt:2:beta"));

        let write = execute_workspace_tool(
            &workspace,
            "write_file",
            json!({ "path": "output.txt", "content": "local" }),
        );
        assert!(write.success);
        assert_eq!(
            fs::read_to_string(workspace.join("output.txt")).unwrap(),
            "local"
        );

        let list = execute_workspace_tool(&workspace, "list_directory", json!({}));
        assert!(list.success);
        assert_eq!(list.text, "file\tinput.txt\nfile\toutput.txt");
    }

    #[test]
    fn local_tools_reject_traversal() {
        let (sandbox, workspace) = workspace();
        fs::write(sandbox.path().join("outside.txt"), "secret").expect("fixture");
        let result =
            execute_workspace_tool(&workspace, "read_file", json!({ "path": "../outside.txt" }));
        assert!(!result.success);
        assert!(result.text.contains("must not contain '..'"));
    }

    #[test]
    fn apply_patch_updates_and_adds_files_inside_workspace() {
        let (_sandbox, workspace) = workspace();
        fs::write(workspace.join("note.txt"), "alpha\nbeta\n").expect("fixture");
        let result = execute_workspace_tool(
            &workspace,
            "apply_patch",
            json!({
                "patch": "*** Begin Patch\n*** Update File: note.txt\n@@\n-alpha\n+patched\n beta\n*** Add File: nested/new.txt\n+created locally\n*** End Patch\n"
            }),
        );

        assert!(result.success, "{}", result.text);
        assert_eq!(
            fs::read_to_string(workspace.join("note.txt")).unwrap(),
            "patched\nbeta\n"
        );
        assert_eq!(
            fs::read_to_string(workspace.join("nested/new.txt")).unwrap(),
            "created locally\n"
        );
    }

    #[test]
    fn apply_patch_rejects_traversal_and_symlinks_and_breaks_hard_links() {
        use std::os::unix::fs::symlink;

        let (sandbox, workspace) = workspace();
        let outside = sandbox.path().join("outside.txt");
        fs::write(&outside, "outside\n").expect("outside fixture");

        let traversal = execute_workspace_tool(
            &workspace,
            "apply_patch",
            json!({
                "patch": "*** Begin Patch\n*** Update File: ../outside.txt\n@@\n-outside\n+escaped\n*** End Patch\n"
            }),
        );
        assert!(!traversal.success);
        assert_eq!(fs::read_to_string(&outside).unwrap(), "outside\n");

        symlink(&outside, workspace.join("linked.txt")).expect("symlink");
        let linked = execute_workspace_tool(
            &workspace,
            "apply_patch",
            json!({
                "patch": "*** Begin Patch\n*** Update File: linked.txt\n@@\n-outside\n+escaped\n*** End Patch\n"
            }),
        );
        assert!(!linked.success);
        assert!(linked.text.contains("symlink"));
        assert_eq!(fs::read_to_string(&outside).unwrap(), "outside\n");

        fs::hard_link(&outside, workspace.join("hard.txt")).expect("hard link");
        let hard_link = execute_workspace_tool(
            &workspace,
            "apply_patch",
            json!({
                "patch": "*** Begin Patch\n*** Update File: hard.txt\n@@\n-outside\n+workspace only\n*** End Patch\n"
            }),
        );
        assert!(hard_link.success, "{}", hard_link.text);
        assert_eq!(fs::read_to_string(&outside).unwrap(), "outside\n");
        assert_eq!(
            fs::read_to_string(workspace.join("hard.txt")).unwrap(),
            "workspace only\n"
        );
    }

    #[test]
    fn search_reports_each_traversal_budget() {
        let (_sandbox, workspace) = workspace();
        fs::write(workspace.join("a.txt"), "needle\n").expect("first file");
        fs::write(workspace.join("b.txt"), "needle\n").expect("second file");
        fs::create_dir(workspace.join("nested")).expect("nested directory");
        fs::create_dir(workspace.join("nested/deeper")).expect("deep directory");
        fs::write(workspace.join("nested/deeper/c.txt"), "needle\n").expect("deep file");
        let arguments = json!({ "query": "needle" });

        let files = search_text_with_limits(
            &workspace,
            arguments.clone(),
            SearchLimits {
                files: 1,
                ..SEARCH_LIMITS
            },
        )
        .expect("file budget");
        assert!(files.contains("search_text truncated: visited files budget reached"));

        let directories = search_text_with_limits(
            &workspace,
            arguments.clone(),
            SearchLimits {
                directories: 1,
                ..SEARCH_LIMITS
            },
        )
        .expect("directory budget");
        assert!(directories.contains("search_text truncated: visited directories budget reached"));

        let depth = search_text_with_limits(
            &workspace,
            arguments.clone(),
            SearchLimits {
                depth: 1,
                ..SEARCH_LIMITS
            },
        )
        .expect("depth budget");
        assert!(depth.contains("search_text truncated: recursion depth budget reached"));

        let bytes = search_text_with_limits(
            &workspace,
            arguments,
            SearchLimits {
                scanned_bytes: 1,
                ..SEARCH_LIMITS
            },
        )
        .expect("byte budget");
        assert!(bytes.contains("search_text truncated: scanned bytes budget reached"));
    }
