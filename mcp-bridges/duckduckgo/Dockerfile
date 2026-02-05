# HTTP bridge for official mcp/duckduckgo Docker image
# Extends the official image with an HTTP bridge layer
FROM mcp/duckduckgo:latest

# The official image is based on Python, so we just add our bridge
WORKDIR /app

# Install aiohttp for the HTTP bridge (curl should already be there or we add it)
RUN pip install --no-cache-dir aiohttp || pip3 install --no-cache-dir aiohttp

# Copy the bridge script
COPY bridge.py .

# Expose HTTP port
EXPOSE 8080

# Run the bridge (which will spawn the MCP server as a subprocess)
CMD ["python", "bridge.py", "8080"]
