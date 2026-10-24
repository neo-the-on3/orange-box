package api

import (
	"errors"
	"io"

	"encoding/json/jsontext"
	jsonv2 "encoding/json/v2"
)

// decodeJSONBody decodes one JSON value from r into v, for the generated
// strict handlers (see templates/strict-http.tmpl).
//
// It uses encoding/json/v2 rather than encoding/json, because v1 matches
// object member names case-insensitively and lets the last duplicate win,
// while kin-openapi validates names exactly: {"trips":[…],"TRIPS":[…]} was
// validated as trips plus an unknown property but decoded from TRIPS. v2
// matches names exactly and rejects duplicate names, so the handlers see
// exactly what was validated. Types with their own UnmarshalJSON (oneOf
// unions, UtcInstant) still decode their contents themselves; the request
// pre-scan rejects case-variant and duplicate names before that.
//
// An empty body returns io.EOF, which the generated code treats as "no body"
// for optional bodies. Data after the value is an error.
func decodeJSONBody(r io.Reader, v any) error {
	dec := jsontext.NewDecoder(r)
	if dec.PeekKind() == jsontext.KindInvalid {
		_, err := dec.ReadToken()
		if err == nil || errors.Is(err, io.EOF) {
			return io.EOF
		}
		return err
	}
	if err := jsonv2.UnmarshalDecode(dec, v); err != nil {
		return err
	}
	if _, err := dec.ReadToken(); !errors.Is(err, io.EOF) {
		return errors.New("unexpected data after the JSON body")
	}
	return nil
}
