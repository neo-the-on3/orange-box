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
# The no-Google rule covers services (Sign-In, Firebase, Google Cloud
# hosting), not open-source images like distroless.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/server /server
USER nonroot:nonroot
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/server"]
