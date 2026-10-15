"""Builds a tiny NES program (iNES, mapper 0) for checking controller input end to end.

Every frame it reads both controller ports and fills the screen with one colour:
player 2 holding anything -> pink; else player 1's first held button:
A red, B green, Select yellow, Start white, Up blue, Down purple, Left orange, Right cyan;
nothing -> black. Written from scratch for Rommio's tests (docs/ios-player-ui-validation.md,
Controllers in the Simulator).

    python3 scripts/ios/make_input_test_rom.py <out.nes>
"""
import sys

ORG = 0xC000
code = bytearray()
labels, fixups = {}, []


def here():
    return ORG + len(code)


def emit(*bs):
    code.extend(bs)


def label(name):
    labels[name] = here()


def abs16(op, name_or_addr):
    emit(op, 0, 0)
    fixups.append((len(code) - 2, name_or_addr, 'abs'))


def rel(op, name):
    emit(op, 0)
    fixups.append((len(code) - 1, name, 'rel'))


BUTTONS, BUTTONS2, COLOR = 0x00, 0x01, 0x02

label('reset')
emit(0x78, 0xD8)                    # SEI, CLD
emit(0xA2, 0x40); abs16(0x8E, 0x4017)   # LDX #$40; STX $4017 (no APU IRQ)
emit(0xA2, 0xFF, 0x9A, 0xE8)        # LDX #$FF; TXS; INX (X=0)
abs16(0x8E, 0x2000); abs16(0x8E, 0x2001); abs16(0x8E, 0x4010)
label('vb1'); abs16(0x2C, 0x2002); rel(0x10, 'vb1')   # BIT $2002; BPL vb1
label('vb2'); abs16(0x2C, 0x2002); rel(0x10, 'vb2')
emit(0xA9, 0x0F, 0x85, COLOR)       # colour = black

label('main')
# Strobe both pads, then read 8 bits from each.
emit(0xA9, 0x01); abs16(0x8D, 0x4016)
emit(0xA9, 0x00); abs16(0x8D, 0x4016)
emit(0x85, BUTTONS, 0x85, BUTTONS2)
emit(0xA2, 0x08)
label('read')
abs16(0xAD, 0x4016); emit(0x4A, 0x26, BUTTONS)        # LDA $4016; LSR A; ROL buttons
abs16(0xAD, 0x4017); emit(0x4A, 0x26, BUTTONS2)       # LDA $4017; LSR A; ROL buttons2
emit(0xCA); rel(0xD0, 'read')                         # DEX; BNE read
# Choose the colour.
emit(0xA5, BUTTONS2); rel(0xF0, 'p1')                 # LDA buttons2; BEQ p1
emit(0xA9, 0x24); abs16(0x4C, 'set')                  # pink
label('p1')
emit(0xA2, 0x00, 0xA5, BUTTONS); rel(0xF0, 'none')    # LDX #0; LDA buttons; BEQ none
label('find')
emit(0x0A); rel(0xB0, 'found'); emit(0xE8); abs16(0x4C, 'find')   # ASL A; BCS found; INX; JMP find
label('found')
abs16(0xBD, 'table'); abs16(0x4C, 'set')              # LDA table,X; JMP set
label('none')
emit(0xA9, 0x0F)
label('set')
emit(0x85, COLOR)
# In vertical blank: backdrop colour, scroll home, background on (blank tiles show the backdrop).
label('wait'); abs16(0x2C, 0x2002); rel(0x10, 'wait')
emit(0xA9, 0x3F); abs16(0x8D, 0x2006); emit(0xA9, 0x00); abs16(0x8D, 0x2006)
emit(0xA5, COLOR); abs16(0x8D, 0x2007)
emit(0xA9, 0x00); abs16(0x8D, 0x2006); abs16(0x8D, 0x2006)
abs16(0x8D, 0x2005); abs16(0x8D, 0x2005)
emit(0xA9, 0x0A); abs16(0x8D, 0x2001)
abs16(0x4C, 'main')

label('nmi')
emit(0x40)                                            # RTI
label('table')
emit(0x16, 0x2A, 0x28, 0x30, 0x12, 0x14, 0x17, 0x1C)  # A B Select Start Up Down Left Right

for offset, target, kind in fixups:
    address = labels[target] if isinstance(target, str) else target
    if kind == 'abs':
        code[offset], code[offset + 1] = address & 0xFF, address >> 8
    else:
        delta = address - (ORG + offset + 1)
        assert -128 <= delta <= 127, target
        code[offset] = delta & 0xFF

prg = bytearray([0xEA] * 0x4000)
prg[:len(code)] = code
for vector, name in ((0x3FFA, 'nmi'), (0x3FFC, 'reset'), (0x3FFE, 'nmi')):
    prg[vector], prg[vector + 1] = labels[name] & 0xFF, labels[name] >> 8
header = b'NES\x1a' + bytes([1, 1, 0, 0]) + bytes(8)
chr_rom = bytes(0x2000)
open(sys.argv[1], 'wb').write(header + bytes(prg) + chr_rom)
print(f'{sys.argv[1]}: {len(code)} bytes of code')
