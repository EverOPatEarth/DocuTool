# DocuTools (Android)

An offline Android document utility based on the two supplied Python desktop tools.

## Features
- DOCX to PDF: select a `.docx` using Android's file picker and save a PDF.
- PDF merger: select multiple PDFs, reorder them, remove individual files, and save a merged PDF.
- No network permission is requested; document processing happens locally.

## Important DOCX conversion limitation
The first offline converter extracts paragraph text from `word/document.xml` and renders it using Android's `PdfDocument`. It preserves readable paragraph content, but it is not a full Microsoft Word layout engine. Complex formatting, tables, embedded images, headers/footers, footnotes, and page layout may not be reproduced. The PDF merger uses PDFBox-Android.

## Build the APK
1. Install Android Studio (with Android SDK Platform 35) and JDK 17.
2. Open this folder in Android Studio and allow Gradle sync.
3. Select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

Alternatively, push this project to a GitHub repository and run the **Build Android APK** workflow. It uploads the APK as a workflow artifact.

## Package
- Application ID: `com.example.docutools`
- App name: `DocuTools`
- Minimum Android: Android 8.0 (API 26)
