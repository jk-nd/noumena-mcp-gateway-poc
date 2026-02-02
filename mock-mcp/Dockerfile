FROM golang:1.21-alpine AS builder
WORKDIR /app
COPY main.go .
RUN go build -o mock-mcp main.go

FROM alpine:latest
WORKDIR /app
COPY --from=builder /app/mock-mcp .
EXPOSE 8080
ENTRYPOINT ["./mock-mcp"]
