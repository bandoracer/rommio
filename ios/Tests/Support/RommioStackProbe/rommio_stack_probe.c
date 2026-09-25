#include "rommio_stack_probe.h"

#include <stdint.h>

// 3 MB below the caller: well inside the 8 MB main-thread stack of a macOS test
// process, and three times the ~1 MB an iPhone gives its main thread.
#define ROMMIO_PROBE_BYTES ((size_t)3 << 20)
#define ROMMIO_PROBE_FILL ((uint8_t)0xA5)

size_t rommio_stack_probe_capacity(void) { return ROMMIO_PROBE_BYTES; }

__attribute__((noinline, optnone)) void rommio_stack_paint(void) {
    volatile uint8_t region[ROMMIO_PROBE_BYTES];
    for (size_t i = 0; i < ROMMIO_PROBE_BYTES; i++) region[i] = ROMMIO_PROBE_FILL;
}

// region[0] is the deepest address (the stack grows down). The first byte that no
// longer holds the pattern marks how deep the measured work reached.
__attribute__((noinline, optnone)) size_t rommio_stack_high_water(void) {
    volatile uint8_t region[ROMMIO_PROBE_BYTES];
    for (size_t i = 0; i < ROMMIO_PROBE_BYTES; i++) {
        if (region[i] != ROMMIO_PROBE_FILL) return ROMMIO_PROBE_BYTES - i;
    }
    return 0;
}
