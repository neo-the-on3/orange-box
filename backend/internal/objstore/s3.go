package objstore

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/aws/smithy-go"
)

// S3Options configures NewS3.
type S3Options struct {
	// Endpoint is the S3 API endpoint, for R2
	// https://<account id>.r2.cloudflarestorage.com.
	Endpoint string
	// Region is the signing region; R2 uses "auto" (the default).
	Region string
	// Bucket holds the objects.
	Bucket string
	// AccessKeyID and SecretAccessKey are the API token's S3 credentials.
	AccessKeyID     string
	SecretAccessKey string
	// HTTPClient, if set, replaces the default client (tests).
	HTTPClient *http.Client
	// MaxAttempts caps attempts per call; 0 is the SDK default (3).
	MaxAttempts int
}

// S3 is a Store on an S3-compatible API (Cloudflare R2).
type S3 struct {
	client *s3.Client
	bucket string
}

// NewS3 returns an S3 store. It makes no request.
func NewS3(o S3Options) (*S3, error) {
	u, err := url.Parse(o.Endpoint)
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" {
		return nil, errors.New("objstore: the S3 endpoint must be an http(s) URL")
	}
	if o.Bucket == "" || o.AccessKeyID == "" || o.SecretAccessKey == "" {
		return nil, errors.New("objstore: bucket, access key id and secret access key are required")
	}
	if o.Region == "" {
		o.Region = "auto"
	}
	opts := s3.Options{
		Region:       o.Region,
		BaseEndpoint: aws.String(o.Endpoint),
		Credentials:  credentials.NewStaticCredentialsProvider(o.AccessKeyID, o.SecretAccessKey, ""),
		// Path-style requests work on R2 and any S3-compatible server
		// without wildcard DNS for the bucket.
		UsePathStyle: true,
		// Only send and check checksums where the API requires them: R2
		// doesn't support every flexible checksum the SDK defaults to.
		RequestChecksumCalculation: aws.RequestChecksumCalculationWhenRequired,
		ResponseChecksumValidation: aws.ResponseChecksumValidationWhenRequired,
	}
	if o.HTTPClient != nil {
		opts.HTTPClient = o.HTTPClient
	}
	if o.MaxAttempts > 0 {
		opts.RetryMaxAttempts = o.MaxAttempts
	}
	return &S3{client: s3.New(opts), bucket: o.Bucket}, nil
}

// Put implements Store.
func (s *S3) Put(ctx context.Context, key string, body []byte) error {
	if err := ValidateKey(key); err != nil {
		return err
	}
	_, err := s.client.PutObject(ctx, &s3.PutObjectInput{
		Bucket:        aws.String(s.bucket),
		Key:           aws.String(key),
		Body:          bytes.NewReader(body),
		ContentLength: aws.Int64(int64(len(body))),
		ContentType:   aws.String("application/octet-stream"),
	})
	if err != nil {
		return fmt.Errorf("objstore: put: %w", err)
	}
	return nil
}

// Get implements Store.
func (s *S3) Get(ctx context.Context, key string) ([]byte, error) {
	if err := ValidateKey(key); err != nil {
		return nil, err
	}
	out, err := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(key)})
	if err != nil {
		if notFound(err) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("objstore: get: %w", err)
	}
	defer func() { _ = out.Body.Close() }()
	b, err := io.ReadAll(out.Body)
	if err != nil {
		return nil, fmt.Errorf("objstore: get: %w", err)
	}
	return b, nil
}

// Delete implements Store. S3 answers 204 for a missing key too.
func (s *S3) Delete(ctx context.Context, key string) error {
	if err := ValidateKey(key); err != nil {
		return err
	}
	_, err := s.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: aws.String(s.bucket), Key: aws.String(key)})
	if err != nil && !notFound(err) {
		return fmt.Errorf("objstore: delete: %w", err)
	}
	return nil
}

func notFound(err error) bool {
	var nsk *types.NoSuchKey
	var nf *types.NotFound
	if errors.As(err, &nsk) || errors.As(err, &nf) {
		return true
	}
	var ae smithy.APIError
	return errors.As(err, &ae) && (ae.ErrorCode() == "NoSuchKey" || ae.ErrorCode() == "NotFound")
}
