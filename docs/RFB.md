# The RFB client

The client in `rfb/` was written from the RFB specification
(`docs/rfbproto.rst`, the `rfbproto/rfbproto` repository).

## Handshake

```
server -> client   "RFB 003.008\n"          (12 bytes)
client -> server   "RFB 003.008\n"          (RFB 3.7 and newer)
server -> client   U8 count, count * U8 security types
client -> server   U8 chosen type            (3.7 and newer)
server -> client   U32 security result       (3.8 only)
client -> server   U8 shared flag
server -> client   U16 width, U16 height, 16 byte pixel format, name
```

Two details are easy to get wrong and were both found by testing against a
real server:

* the minor version lives in bytes 8..10 of the version string, not in
  bytes 4..6 (those hold the major version);
* RFB 3.8 servers send a four byte `SecurityResult` after the security
  choice, even when the security type is `None`. Missing it shifts every
  following field by four bytes.

A third one cost the most time and is worth repeating:

* **the client answers the security type list with a single byte.**
  RFB 3.3 sends a 32 bit value, but from 3.7 on the server writes one byte
  and never skips the three bytes of padding that some clients send. Three
  stray bytes in the stream make every following message - the
  `ClientInit`, the `SetPixelFormat`, everything - start at the wrong
  offset, and the server usually complains about the pixel format rather
  than about the real problem.

All three were found with a byte level proxy in front of a real TigerVNC
server, which is the fastest way to find a desynchronisation.

Both RFB 3.3 (single 32 bit security type, no security result) and RFB
3.7/3.8 are supported.

## Security

`VncAuth` implements the VNC challenge/response: the password is truncated
to eight bytes, the bits of every byte are reversed, and the 16 byte
challenge is encrypted with DES in ECB mode. The expected answers in
`VncAuthTest` were produced with an independent implementation
(pycryptodome), so a mistake in the library cannot cancel out with the same
mistake in the test.

## Pixel format

The client always asks for the canonical format:

```
bits per pixel   32
depth            24
big endian       false
true colour      true
red max/shift    255 / 16
green max/shift  255 / 8
blue max/shift   255 / 0
```

The bytes of a pixel are therefore `B, G, R, unused`.

The spec defines two shorter pixel representations:

* a **CPIXEL** holds the three significant bytes of the pixel, taken from
  the least significant end (or the most significant end when the colour
  components sit in the high bytes);
* a **TPIXEL** always holds red, green, blue in that order.

`RfbPixelFormat.cpixelMode` decides which of the two applies, so the
decoders work with any format the server may insist on.

## Encodings

| Encoding | Id | Notes |
| --- | --- | --- |
| Raw | 0 | `width * height` pixels. |
| CopyRect | 1 | Source rectangle, copied inside the framebuffer. |
| RRE | 2 | Sub-rectangles with their own colour. |
| CoRRE | 4 | A background colour plus coloured sub-rectangles. |
| Hextile | 5 | 16x16 tiles, raw or with a background, a foreground and sub-rectangles. |
| zlib | 6 | The whole rectangle as one zlib stream (U32 length first). |
| ZRLE | 16 | 64x64 tiles, zlib compressed, with raw, solid, palette and run length sub-encodings. |
| Tight | 7 | zlib with the copy, palette and gradient filters, or a fill. The JPEG variant is not supported, a server that insists on it is refused. |

Pseudo encodings: `DesktopSize` (-223), `LastRect` (-224) and
`ExtendedDesktopSize` (-308). The client message `SetDesktopSize` (251) asks
the server to change the framebuffer size.

The client only advertises encodings it can decode correctly. Advertising an
encoding that is not implemented is allowed by the specification but leads to
a corrupt picture, so the default list of `RfbOptions` is deliberately
conservative: `ZRLE, Tight, Hextile, zlib, Raw, RRE, CoRRE, CopyRect` plus
the pseudo encodings.

The order of the fields inside a sub-rectangle is worth a word, because it
is easy to get wrong: in RRE, CoRRE and in the coloured sub-rectangles of
Hextile the *colour* comes first and the position follows. The decoder of
this library follows the specification and TigerVNC, the first version of it
did not and was caught by the unit tests.

## Verification

`LiveServerTest` runs against a real TigerVNC server and forces one
encoding at a time:

```bash
# a TigerVNC server with a known root window colour
xsetroot -solid "#3366CC"
gradle :rfb:cleanTest :rfb:test --no-daemon --no-build-cache \
    -Dlinuxdesk.test.port=5901 -Dlinuxdesk.test.rootColor=3366CC
```

For every encoding the test checks that the decoded framebuffer contains
exactly the colour the X server painted, which validates the byte order, the
length prefixes, the tile layout and the pixel format in one go.
