# HTTP bridge for mcp-server-fetch
FROM python:3.11-slim

WORKDIR /app

# Install curl for health checks
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Install mcp-server-fetch
RUN pip install --no-cache-dir mcp-server-fetch aiohttp

# Copy the bridge script
COPY bridge.py .

# Expose HTTP port
EXPOSE 8080

# Run the bridge
CMD ["python", "bridge.py", "8080"]
