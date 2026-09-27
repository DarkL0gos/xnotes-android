package com.xnotes.format

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class XmlDigestsJvmTest {
    @Test fun flowXmlBytes() = assertEquals(XmlDigests.FLOW_BYTES, XmlDigests.flowBytesDigest())
    @Test fun flowXmlReadsBack() = assertEquals(XmlDigests.FLOW_READ, XmlDigests.flowReadDigest())
    @Test fun svgScene() = assertNull(XmlDigests.sceneMismatch())
}
