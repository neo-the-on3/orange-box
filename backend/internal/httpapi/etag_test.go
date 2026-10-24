package httpapi

import "testing"

func TestEntityTagMatching(t *testing.T) {
	t.Parallel()
	cur := vehicleETag(3)
	if cur != `"v3"` {
		t.Fatalf("vehicleETag(3) = %s", cur)
	}
	for _, c := range []struct {
		header         string
		match, noMatch bool
	}{
		{`"v3"`, true, true},
		{` "v1" , "v3" `, true, true},
		{`*`, true, true},
		{`W/"v3"`, false, true}, // weak tags never match If-Match
		{`"v2"`, false, false},
		{`v3`, false, false}, // unquoted: malformed
		{``, false, false},
		{`"v3`, false, false},
	} {
		if got := ifMatch(c.header, cur); got != c.match {
			t.Errorf("ifMatch(%q) = %v", c.header, got)
		}
		if got := ifNoneMatch(c.header, cur); got != c.noMatch {
			t.Errorf("ifNoneMatch(%q) = %v", c.header, got)
		}
	}
}
