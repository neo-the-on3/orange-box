# syntax=docker/dockerfile:1

# Base images are pinned by digest for reproducible builds. To update, run
# `docker buildx imagetools inspect <image:tag>` and replace the digest.

# Build stage: a static, CGO-free binary.
FROM golang:1.27-alpine@sha256:b8c9b82affbbb82a020bbcf998dad2ba9516944ac7e883524642a481a9505e9c AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/server ./cmd/server

# Runtime stage: distroless static image, runs as a non-root user.
# The no-Google rule covers services (Sign-In, Firebase, Google Cloud
# hosting), not open-source images like distroless.
FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
COPY --from=build /out/server /server
USER nonroot:nonroot
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/server"]
