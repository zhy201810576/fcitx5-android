/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MtPhotosJsonTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun guessMimeByExtension() {
        assertEquals("image/png", guessMime("png"))
        assertEquals("image/png", guessMime("PNG"))
        assertEquals("image/webp", guessMime("webp"))
        assertEquals("image/gif", guessMime("gif"))
        assertEquals("image/heic", guessMime("heic"))
        assertEquals("image/heif", guessMime("heif"))
        assertEquals("image/jpeg", guessMime("jpg"))
        assertEquals("image/jpeg", guessMime("jpeg"))
        assertEquals("image/jpeg", guessMime("unknown"))
    }

    @Test
    fun mtFileDecodeUppercaseMd5() {
        val raw = """[{"id":1,"MD5":"abc123","fileName":"a.png"}]"""
        val files = json.decodeFromString<List<MtFile>>(raw)
        assertEquals(1, files.size)
        assertEquals(1L, files[0].id)
        assertEquals("abc123", files[0].md5)
        assertEquals("a.png", files[0].fileName)
    }

    @Test
    fun mtFileDecodeLowercaseMd5() {
        val raw = """[{"id":2,"md5":"def456","fileName":"b.jpg"}]"""
        val files = json.decodeFromString<List<MtFile>>(raw)
        assertEquals(1, files.size)
        assertEquals(2L, files[0].id)
        assertEquals("def456", files[0].md5)
    }

    @Test
    fun galleryListDecode() {
        val raw = """[{"id":10,"name":"相册A","forUpload":true},{"id":11,"name":"相册B"}]"""
        val galleries = json.decodeFromString<List<MtGallery>>(raw)
        assertEquals(2, galleries.size)
        assertEquals(10L, galleries[0].id)
        assertEquals("相册A", galleries[0].name)
        assertTrue(galleries[0].forUpload)
        assertFalse(galleries[1].forUpload)
    }

    @Test
    fun fileListResponseListShape() {
        val raw = """{"list":[{"id":1,"MD5":"a"},{"id":2,"MD5":"b"}]}"""
        val resp = json.decodeFromString<MtFileListResponse>(raw)
        assertEquals(2, resp.list?.size)
        assertEquals("a", resp.list?.get(0)?.md5)
    }

    @Test
    fun fileListResponseResultShape() {
        val raw = """
            {"result":[
                {"day":"2026-01-01","list":[{"id":1,"MD5":"a"}]},
                {"day":"2026-01-02","list":[{"id":2,"md5":"b"}]}
            ]}
        """.trimIndent()
        val resp = json.decodeFromString<MtFileListResponse>(raw)
        assertEquals(2, resp.result?.size)
        assertEquals("a", resp.result?.get(0)?.list?.get(0)?.md5)
        assertEquals("b", resp.result?.get(1)?.list?.get(0)?.md5)
    }

    @Test
    fun searchRequestOmitsNullFields() {
        val req = MtSearchRequest(searchKey = "hello")
        val s = json.encodeToString(req)
        assertTrue(s.contains("\"searchKey\":\"hello\""))
        assertFalse(s.contains("searchType"))
        assertFalse(s.contains("galleryIds"))
        assertFalse(s.contains("count"))
    }

    @Test
    fun searchRequestIncludesOptionalFields() {
        val req = MtSearchRequest(
            searchType = "v1",
            searchKey = "dog",
            galleryIds = listOf(1, 2),
            tagUseOr = true,
        )
        val s = json.encodeToString(req)
        assertTrue(s.contains("\"searchType\":\"v1\""))
        assertTrue(s.contains("\"galleryIds\":[1,2]"))
        assertTrue(s.contains("\"tagUseOr\":true"))
    }
}
