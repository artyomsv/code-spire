package dev.codespire.harness.claudecode;

/**
 * Stream-json lines in the shapes Claude Code 2.1.319 emitted in the 2026-11-11 measurement (harness reviews 3b, Task
 * 3's record), with every id, text and path replaced by a TEST- value.
 */
final class ClaudeCodeLines {

    static final String TEXT = """
            {"type":"assistant","message":{"model":"claude-haiku-5-5","id":"msg_TEST-1","type":"message",\
            "role":"assistant","content":[{"type":"text","text":"TEST-answer"}],"stop_reason":null,\
            "usage":{"input_tokens":4,"cache_creation_input_tokens":0,"cache_read_input_tokens":1767,\
            "output_tokens":8}},"parent_tool_use_id":null,"session_id":"TEST-session","uuid":"TEST-uuid-1"}""";

    static final String THREE_BLOCKS = """
            {"type":"assistant","message":{"model":"claude-haiku-5-5","id":"msg_TEST-2","type":"message",\
            "role":"assistant","content":[\
            {"type":"text","text":"TEST-answer"},\
            {"type":"thinking","thinking":"TEST-thought","signature":"TEST-signature"},\
            {"type":"tool_use","id":"toolu_TEST-1","name":"Bash","input":{"command":"git diff TEST-base HEAD",\
            "description":"TEST-description"},"caller":{"type":"direct"}}],\
            "stop_reason":null,"usage":{"input_tokens":4,"cache_creation_input_tokens":0,\
            "cache_read_input_tokens":1767,"output_tokens":8}},"parent_tool_use_id":null,\
            "session_id":"TEST-session","uuid":"TEST-uuid-2"}""";

    static final String TOOL_RESULT = """
            {"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_TEST-1","type":"tool_result",\
            "content":"TEST-diff","is_error":false}]},"parent_tool_use_id":null,"session_id":"TEST-session",\
            "uuid":"TEST-uuid-3","tool_use_result":{"stdout":"TEST-diff","stderr":"","interrupted":false,\
            "isImage":false,"noOutputExpected":false}}""";

    static final String RESULT_SUCCESS = """
            {"duration_api_ms":3686,"stop_reason":"end_turn","session_id":"TEST-session","total_cost_usd":0.01,\
            "usage":{"input_tokens":10,"cache_creation_input_tokens":30,"cache_read_input_tokens":20,\
            "output_tokens":40,"output_tokens_details":{"thinking_tokens":5},"service_tier":"standard"},\
            "modelUsage":{"claude-haiku-5-5":{"inputTokens":10,"outputTokens":40,"cacheReadInputTokens":20,\
            "cacheCreationInputTokens":30}},"permission_denials":[],"terminal_reason":"completed",\
            "is_error":false,"num_turns":3,"subtype":"success","api_error_status":null,"result":"TEST-answer",\
            "type":"result","uuid":"TEST-uuid-4"}""";

    /** Measured: an error result says {@code "subtype":"success"}; only {@code is_error} tells. */
    static final String RESULT_ERROR = """
            {"duration_api_ms":0,"stop_reason":"stop_sequence","session_id":"TEST-session","total_cost_usd":0,\
            "usage":{"output_tokens_details":{"thinking_tokens":0},"input_tokens":0,\
            "cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":0},"modelUsage":{},\
            "permission_denials":[],"terminal_reason":"api_error","is_error":true,"num_turns":1,\
            "subtype":"success","api_error_status":null,"api_error":"connection_failed",\
            "result":"API Error: TEST-unreachable","type":"result","uuid":"TEST-uuid-5"}""";

    static final String SYSTEM_INIT = """
            {"type":"system","subtype":"init","cwd":"/workspace","session_id":"TEST-session","tools":["Bash",\
            "Edit","Read"],"mcp_servers":[],"model":"claude-haiku-5-5","permissionMode":"bypassPermissions",\
            "slash_commands":[],"apiKeySource":"ANTHROPIC_API_KEY","claude_code_version":"2.1.319",\
            "uuid":"TEST-uuid-6"}""";

    static final String API_RETRY = """
            {"type":"system","subtype":"api_retry","attempt":1,"max_retries":10,"retry_delay_ms":597,\
            "error_status":null,"error":"unknown","session_id":"TEST-session","uuid":"TEST-uuid-7"}""";

    private ClaudeCodeLines() {
    }
}
