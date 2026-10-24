# syntax=docker/dockerfile:1

# Build stage: a static, CGO-free binary.
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod ./
# go.sum joins the COPY above once the module has dependencies.
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/server ./cmd/server

# Runtime stage: distroless static image, runs as a non-root user.
# Chainguard's image is used instead of gcr.io/distroless to keep Google
# services out of the stack.
FROM cgr.dev/chainguard/static:latest
COPY --from=build /out/server /server
USER 65532:65532
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/server"]
