# Build stage
FROM golang:1.26-alpine AS builder

WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-w -s" -o plonker-server .

# Runtime stage
FROM alpine:3.20

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /app
COPY --from=builder /app/plonker-server ./server

EXPOSE 8000
ENV PORT=8000
ENV JAM_TRUST_PROXY=true
ENV JAM_MAX_MEMBERS=5
ENV JAM_STATE_HEARTBEAT_MS=5000
ENV JAM_MAX_UPCOMING_QUEUE=25
ENV JAM_MAX_PARTIES=200
ENV JAM_CREATE_RATE_PER_MINUTE=2
ENV JAM_CONNECTION_IDLE_MS=900000

CMD ["./server"]
