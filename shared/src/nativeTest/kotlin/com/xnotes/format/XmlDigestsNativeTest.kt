package com.xnotes.format

import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals

/** flow.xml and SVG import on Kotlin/Native give exactly what the JVM gives. */
class XmlDigestsNativeTest {
    @Test fun flowXmlBytes() = assertEquals(XmlDigests.FLOW_BYTES, XmlDigests.flowBytesDigest())
    @Test fun flowXmlReadsBack() = assertEquals(XmlDigests.FLOW_READ, XmlDigests.flowReadDigest())
    @Test fun svgScene() = assertNull(XmlDigests.sceneMismatch())
}
