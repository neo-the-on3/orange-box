// Package testutil has helpers shared by tests.
package testutil

import (
	"crypto/rand"
	"math/big"

	"github.com/neo-the-on3/orange-box/backend/internal/config"
)

// RandomTestCode returns a random AUTH_TEST_CODE that passes
// config.ValidateTestCode, so no fixed code ever lives in the repo.
func RandomTestCode() string {
	for {
		n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
		if err != nil {
			panic(err)
		}
		code := n.Text(10)
		for len(code) < 6 {
			code = "0" + code
		}
		if config.ValidateTestCode(code) == nil {
			return code
		}
	}
}
