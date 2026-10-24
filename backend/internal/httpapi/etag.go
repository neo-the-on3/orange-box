package httpapi

import (
	"strconv"
	"strings"
)

// vehicleETag is the strong entity tag of a vehicle representation. The
// version is bumped by every change, so it identifies the representation.
func vehicleETag(version int64) string {
	return `"v` + strconv.FormatInt(version, 10) + `"`
}

// entityTags splits an If-Match / If-None-Match field value into its
// entity-tags (RFC 9110 §8.8.3). "*" comes back as a single "*". Malformed
// members are dropped, so they never match.
func entityTags(v string) []string {
	var tags []string
	for part := range strings.SplitSeq(v, ",") {
		part = strings.TrimSpace(part)
		switch {
		case part == "*":
			tags = append(tags, part)
		case len(part) >= 2 && strings.HasPrefix(part, `"`) && strings.HasSuffix(part, `"`),
			len(part) >= 4 && strings.HasPrefix(part, `W/"`) && strings.HasSuffix(part, `"`):
			tags = append(tags, part)
		}
	}
	return tags
}

// ifMatch evaluates If-Match against the current strong ETag with the strong
// comparison (RFC 9110 §13.1.1): a weak tag never matches.
func ifMatch(header, current string) bool {
	for _, t := range entityTags(header) {
		if t == "*" || t == current {
			return true
		}
	}
	return false
}

// ifNoneMatch reports whether If-None-Match matches the current ETag with the
// weak comparison (RFC 9110 §13.1.2), i.e. a GET should answer 304.
func ifNoneMatch(header, current string) bool {
	cur := strings.TrimPrefix(current, "W/")
	for _, t := range entityTags(header) {
		if t == "*" || strings.TrimPrefix(t, "W/") == cur {
			return true
		}
	}
	return false
}
