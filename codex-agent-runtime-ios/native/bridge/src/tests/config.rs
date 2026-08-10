use super::*;

    #[test]
    fn configuration_rejects_equal_workspace_and_codex_home() {
        let (sandbox, workspace) = workspace();
        let error = configuration(sandbox.path(), &workspace, &workspace)
            .validate()
            .expect_err("equal paths must fail");
        assert!(error.contains("disjoint"));
    }

    #[test]
    fn configuration_rejects_codex_home_inside_workspace() {
        let (sandbox, workspace) = workspace();
        let nested = workspace.join("state");
        let error = configuration(sandbox.path(), &workspace, &nested)
            .validate()
            .expect_err("nested Codex home must fail");
        assert!(error.contains("disjoint"));
        assert!(!nested.exists(), "rejected Codex home must not be created");
    }

    #[test]
    fn configuration_rejects_workspace_inside_codex_home() {
        let sandbox = TempDir::new().expect("sandbox");
        let codex_home = sandbox.path().join("state");
        let workspace = codex_home.join("workspace");
        fs::create_dir_all(&workspace).expect("nested workspace");
        let error = configuration(sandbox.path(), &workspace, &codex_home)
            .validate()
            .expect_err("nested workspace must fail");
        assert!(error.contains("disjoint"));
    }

    #[test]
    fn configuration_rejects_workspace_outside_sandbox() {
        let sandbox = TempDir::new().expect("sandbox");
        let outside = TempDir::new().expect("outside");
        let codex_home = sandbox.path().join("state");
        let error = configuration(sandbox.path(), outside.path(), &codex_home)
            .validate()
            .expect_err("outside workspace must fail");
        assert!(error.contains("escapes"));
    }

    #[test]
    fn configuration_rejects_codex_home_outside_sandbox() {
        let (sandbox, workspace) = workspace();
        let outside = TempDir::new().expect("outside");
        let error = configuration(sandbox.path(), &workspace, outside.path())
            .validate()
            .expect_err("outside Codex home must fail");
        assert!(error.contains("escapes"));
    }

    #[test]
    fn configuration_accepts_sibling_directories() {
        let (sandbox, workspace) = workspace();
        let codex_home = sandbox.path().join("state");
        let paths = configuration(sandbox.path(), &workspace, &codex_home)
            .validate()
            .expect("sibling paths");
        assert_eq!(paths.workspace, workspace);
        assert_eq!(paths.codex_home, codex_home.canonicalize().expect("Codex home"));
    }

    #[test]
    fn duplicate_runtime_ownership_is_rejected_and_clean_release_is_reusable() {
        let (sandbox, workspace) = workspace();
        let home = sandbox.path().join("state");
        let paths = configuration(sandbox.path(), &workspace, &home)
            .validate()
            .expect("configuration");
        let first = CodexHomeLease::acquire(&paths.codex_home).expect("first lease");
        let error = CodexHomeLease::acquire(&paths.codex_home)
            .err()
            .expect("duplicate lease must fail");
        assert!(error.contains("already owns"));
        drop(first);
        drop(CodexHomeLease::acquire(&paths.codex_home).expect("reused lease"));
    }

    #[test]
    fn failed_start_path_releases_runtime_ownership() {
        let (sandbox, workspace) = workspace();
        let home = sandbox.path().join("state");
        let paths = configuration(sandbox.path(), &workspace, &home)
            .validate()
            .expect("configuration");
        let failed: Result<(), String> = (|| {
            let _lease = CodexHomeLease::acquire(&paths.codex_home)?;
            Err("simulated startup failure".to_string())
        })();
        assert!(failed.is_err());
        drop(CodexHomeLease::acquire(&paths.codex_home).expect("lease after failed start"));
    }

    #[test]
    fn configuration_rejects_paths_before_creating_outside_the_sandbox() {
        let sandbox = TempDir::new().expect("sandbox");
        let workspace = sandbox.path().join("workspace");
        fs::create_dir(&workspace).expect("workspace");
        let outside_parent = TempDir::new().expect("outside parent");
        let outside = outside_parent.path().join("state");
        let configuration = RuntimeConfiguration {
            sandbox_root_path: sandbox.path().to_path_buf(),
            workspace_path: workspace,
            codex_home_path: outside.clone(),
        };

        assert!(configuration.validate().is_err());
        assert!(!outside.exists());
    }
