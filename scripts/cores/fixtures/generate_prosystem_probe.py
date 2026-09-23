#!/usr/bin/env python3
"""Write a tiny original 7800 NTSC ROM for boot and snapshot validation."""
import argparse
import pathlib


def generate(path: pathlib.Path) -> None:
    header = bytearray(128)
    header[0] = 1
    header[1:10] = b'ATARI7800'
    header[17:35] = b'Rommio color probe'
    header[49:53] = (16384).to_bytes(4, 'big')
    header[55:57] = bytes([1, 1])  # Joysticks.
    rom = bytearray([0xEA] * 16384)
    # SEI; CLD; LDX #$FF; TXS; LDA #$1C; STA BACKGRND ($20); JMP $C009.
    rom[:12] = bytes([0x78, 0xD8, 0xA2, 0xFF, 0x9A, 0xA9, 0x1C, 0x85, 0x20,
                      0x4C, 0x09, 0xC0])
    for offset in (0x3FFA, 0x3FFC, 0x3FFE):
        rom[offset:offset + 2] = bytes([0x00, 0xC0])
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(header + rom)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=pathlib.Path)
    generate(parser.parse_args().output)
