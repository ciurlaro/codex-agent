use super::*;

    #[test]
    fn api_buffers_round_trip_and_free() {
        let mut buffer = CodexAgentIosBuffer::default();
        write_buffer(&mut buffer, "Grüezi".to_string()).expect("buffer");
        let bytes = unsafe { slice::from_raw_parts(buffer.data, buffer.length) };
        assert_eq!(str::from_utf8(bytes).unwrap(), "Grüezi");
        codex_agent_ios_buffer_free(&mut buffer);
        assert!(buffer.data.is_null());
        assert_eq!(buffer.length, 0);
    }

    #[tokio::test]
    async fn embedded_host_leaves_the_handshake_to_the_json_rpc_client() {
        let (sandbox, workspace) = workspace();
        let codex_home = sandbox.path().join("state");
        fs::create_dir(&codex_home).expect("state");
        let paths = RuntimePaths {
            workspace,
            codex_home: codex_home.canonicalize().expect("canonical state"),
        };
        let client = start_app_server(&paths).await.expect("uninitialized host");
        let initialize: ClientRequest = serde_json::from_value(json!({
            "id": 1,
            "method": "initialize",
            "params": {
                "clientInfo": {
                    "name": "bridge-test",
                    "version": "0.0.0",
                    "title": "Bridge Test"
                },
                "capabilities": {
                    "experimentalApi": true,
                    "mcpServerOpenaiFormElicitation": false
                }
            }
        }))
        .expect("initialize request");
        assert!(client.request(initialize).await.expect("transport").is_ok());
        let initialized: ClientNotification =
            serde_json::from_value(json!({ "method": "initialized" }))
                .expect("initialized notification");
        client.notify(initialized).await.expect("initialized");

        let duplicate: ClientRequest = serde_json::from_value(json!({
            "id": 2,
            "method": "initialize",
            "params": {
                "clientInfo": { "name": "duplicate", "version": "0.0.0" },
                "capabilities": { "experimentalApi": true }
            }
        }))
        .expect("duplicate initialize request");
        assert!(client.request(duplicate).await.expect("transport").is_err());
        client.shutdown().await.expect("shutdown");
    }
