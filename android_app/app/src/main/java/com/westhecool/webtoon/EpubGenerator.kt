package com.westhecool.webtoon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.URL
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

object EpubGenerator {

    private const val MAX_HEIGHT = 1680

    suspend fun createEpub(
        context: Context,
        comicInfo: ComicInfo,
        chapters: List<Chapter>,
        progressCallback: (String, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "epub_temp_${System.currentTimeMillis()}")
        workDir.mkdirs()

        val oebpsDir = File(workDir, "OEBPS")
        oebpsDir.mkdirs()
        val textDir = File(oebpsDir, "Text")
        textDir.mkdirs()
        val imagesDir = File(oebpsDir, "Images")
        imagesDir.mkdirs()
        val metaInfDir = File(workDir, "META-INF")
        metaInfDir.mkdirs()

        // Create mimetype
        File(workDir, "mimetype").writeText("application/epub+zip")

        // Create container.xml
        File(metaInfDir, "container.xml").writeText(
            """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
   <rootfiles>
      <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
   </rootfiles>
</container>"""
        )

        val imageFiles = mutableListOf<String>()
        val chapterFiles = mutableListOf<String>()

        // Process chapters
        for ((index, chapter) in chapters.withIndex()) {
            val chapterNum = index + 1
            progressCallback("Downloading Chapter $chapterNum: ${chapter.title}", (index * 100) / chapters.size)

            val imageUrls = WebtoonScraper.getChapterImages(chapter.url)
            val chapterImages = mutableListOf<String>()

            for ((imgIndex, imgUrl) in imageUrls.withIndex()) {
                try {
                    val bitmap = downloadBitmap(imgUrl, chapter.url) // Pass referer
                    if (bitmap != null) {
                        val splits = splitBitmap(bitmap)
                        for ((splitIndex, splitParams) in splits.withIndex()) {
                            val fileName = "ch${chapterNum}_img${imgIndex}_$splitIndex.jpg"
                            val file = File(imagesDir, fileName)
                            FileOutputStream(file).use { out ->
                                splitParams.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            chapterImages.add(fileName)
                            imageFiles.add(fileName)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("EpubGenerator", "Error downloading image: $imgUrl", e)
                }
            }

            // Create XHTML
            val xhtmlFile = "chapter$chapterNum.xhtml"
            chapterFiles.add(xhtmlFile)
            val content = StringBuilder()
            content.append("""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
<title>${chapter.title}</title>
<style type="text/css">
body { margin: 0; padding: 0; }
img { width: 100%; height: auto; display: block; }
</style>
</head>
<body>
<h1>${chapter.title}</h1>
""")
            for (img in chapterImages) {
                content.append("<img src=\"../Images/$img\" alt=\"image\" />\n")
            }
            content.append("</body></html>")
            File(textDir, xhtmlFile).writeText(content.toString())
        }

        // Create content.opf
        val uuid = UUID.randomUUID().toString()
        createContentOpf(oebpsDir, comicInfo, uuid, chapterFiles, imageFiles)

        // Create toc.ncx
        createTocNcx(oebpsDir, comicInfo, uuid, chapterFiles, chapters)

        // Create nav.xhtml (optional for EPUB 3 but good practice)
        createNavXhtml(oebpsDir, comicInfo, chapterFiles, chapters)

        // Zip it
        val safeTitle = comicInfo.title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val outputFile = File(context.getExternalFilesDir(null), "$safeTitle.epub")
        zipDirectory(workDir, outputFile)

        // Cleanup
        workDir.deleteRecursively()

        outputFile
    }

    private fun downloadBitmap(url: String, referer: String): Bitmap? {
        val conn = URL(url).openConnection()
        conn.setRequestProperty("Referer", referer)
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.connect()
        val stream = conn.getInputStream()
        return BitmapFactory.decodeStream(stream)
    }

    private fun splitBitmap(bitmap: Bitmap): List<Bitmap> {
        val list = mutableListOf<Bitmap>()
        var y = 0
        while (y < bitmap.height) {
            val h = min(MAX_HEIGHT, bitmap.height - y)
            val split = Bitmap.createBitmap(bitmap, 0, y, bitmap.width, h)
            list.add(split)
            y += h
        }
        return list
    }

    private fun createContentOpf(dir: File, info: ComicInfo, uuid: String, chapters: List<String>, images: List<String>) {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookID" version="2.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
        <dc:title>${info.title}</dc:title>
        <dc:creator opf:role="aut">${info.author}</dc:creator>
        <dc:language>en</dc:language>
        <dc:identifier id="BookID">urn:uuid:$uuid</dc:identifier>
    </metadata>
    <manifest>
        <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
""")
        for (ch in chapters) {
            sb.append("        <item id=\"${ch.substringBefore('.')}\" href=\"Text/$ch\" media-type=\"application/xhtml+xml\"/>\n")
        }
        for (img in images) {
             val mime = if (img.endsWith(".png")) "image/png" else "image/jpeg"
             sb.append("        <item id=\"$img\" href=\"Images/$img\" media-type=\"$mime\"/>\n")
        }
        sb.append("""    </manifest>
    <spine toc="ncx">
""")
        for (ch in chapters) {
            sb.append("        <itemref idref=\"${ch.substringBefore('.')}\"/>\n")
        }
        sb.append("""    </spine>
</package>""")
        File(dir, "content.opf").writeText(sb.toString())
    }

    private fun createTocNcx(dir: File, info: ComicInfo, uuid: String, chapters: List<String>, chapterData: List<Chapter>) {
         val sb = StringBuilder()
         sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
    <head>
        <meta name="dtb:uid" content="urn:uuid:$uuid"/>
        <meta name="dtb:depth" content="1"/>
        <meta name="dtb:totalPageCount" content="0"/>
        <meta name="dtb:maxPageNumber" content="0"/>
    </head>
    <docTitle>
        <text>${info.title}</text>
    </docTitle>
    <navMap>
""")
        for ((i, ch) in chapters.withIndex()) {
            sb.append("""        <navPoint id="navPoint-${i+1}" playOrder="${i+1}">
            <navLabel>
                <text>${chapterData[i].title}</text>
            </navLabel>
            <content src="Text/$ch"/>
        </navPoint>
""")
        }
        sb.append("""    </navMap>
</ncx>""")
        File(dir, "toc.ncx").writeText(sb.toString())
    }

    private fun createNavXhtml(dir: File, info: ComicInfo, chapters: List<String>, chapterData: List<Chapter>) {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
    <title>${info.title}</title>
</head>
<body>
    <nav epub:type="toc" id="toc">
        <h1>Table of Contents</h1>
        <ol>
""")
        for ((i, ch) in chapters.withIndex()) {
            sb.append("            <li><a href=\"Text/$ch\">${chapterData[i].title}</a></li>\n")
        }
        sb.append("""        </ol>
    </nav>
</body>
</html>""")
        File(dir, "nav.xhtml").writeText(sb.toString())
    }

    private fun zipDirectory(sourceDir: File, outputFile: File) {
        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            // Add mimetype first, uncompressed
            val mimetype = File(sourceDir, "mimetype")
            if (mimetype.exists()) {
                val entry = ZipEntry("mimetype")
                entry.method = ZipEntry.STORED
                entry.size = mimetype.length()
                entry.crc = calculateCrc(mimetype)
                zos.putNextEntry(entry)
                mimetype.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            sourceDir.walk().forEach { file ->
                if (file.isFile && file.name != "mimetype") {
                    val path = file.relativeTo(sourceDir).path
                    val entry = ZipEntry(path)
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    private fun calculateCrc(file: File): Long {
        val crc = java.util.zip.CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                crc.update(buffer, 0, bytesRead)
            }
        }
        return crc.value
    }
}
