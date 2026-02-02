package main

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"
)

// Simple mock MCP server that echoes requests back
func main() {
	http.HandleFunc("/tools/", handleToolCall)
	http.HandleFunc("/health", handleHealth)

	port := "8080"
	log.Printf("Mock MCP Server starting on port %s", port)
	log.Fatal(http.ListenAndServe(":"+port, nil))
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	json.NewEncoder(w).Encode(map[string]string{
		"status":  "ok",
		"service": "mock-mcp",
	})
}

func handleToolCall(w http.ResponseWriter, r *http.Request) {
	// Extract tool name from path: /tools/{tool_name}
	toolName := r.URL.Path[len("/tools/"):]

	// Parse request body
	var params map[string]interface{}
	if r.Body != nil {
		json.NewDecoder(r.Body).Decode(&params)
	}

	log.Printf("Tool call: %s with params: %v", toolName, params)

	// Return mock success response
	response := map[string]interface{}{
		"success":   true,
		"tool":      toolName,
		"timestamp": time.Now().Format(time.RFC3339),
		"message":   fmt.Sprintf("Mock execution of '%s' completed successfully", toolName),
		"echo":      params,
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(response)
}
