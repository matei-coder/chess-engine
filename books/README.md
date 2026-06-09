# Opening Books

Put Polyglot `.bin` opening books here. Donna opening books are Polyglot books, so the
engine can read them directly.

Example:

```bash
curl -L -o books/gm2001.bin https://github.com/michaeldv/donna_opening_books/raw/master/gm2001.bin
export DONNA_BOOK=books/gm2001.bin
```

You can also set the UCI option:

```text
setoption name BookFile value books/gm2001.bin
```
