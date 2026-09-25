#ifndef ROMMIO_STACK_PROBE_H
#define ROMMIO_STACK_PROBE_H

#include <stddef.h>

/// Measures how much of the calling thread's stack a piece of work uses.
///
/// `rommio_stack_paint()` fills the stack below the caller with a pattern;
/// `rommio_stack_high_water()` reports how many bytes below the caller were
/// overwritten since. Call both from the same function (same stack depth), with
/// the measured work in between. Test support only: it reads and writes stack
/// memory below the current frame on purpose.
void rommio_stack_paint(void);
size_t rommio_stack_high_water(void);
size_t rommio_stack_probe_capacity(void);

#endif
