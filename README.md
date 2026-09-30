# Privacy Low Vision PDF

Open-source Android PDF viewer focused on privacy and accessibility.

## MVP goals

- Android 9 (API 28) and newer.
- Open PDFs through Android's system document picker.
- No `MANAGE_EXTERNAL_STORAGE` and no broad storage/media permission.
- No ads, analytics, account, tracking, or Internet permission.
- PDF viewing, scrolling, zoom, text search, and text selection through AndroidX PDF.
- Text reflow for low-vision reading is planned next.
- Android Text-to-Speech is planned after reflow.

## Privacy

The application does not request Internet permission. PDF files are selected explicitly by the user through Android's Storage Access Framework.

## License

Apache License 2.0.
