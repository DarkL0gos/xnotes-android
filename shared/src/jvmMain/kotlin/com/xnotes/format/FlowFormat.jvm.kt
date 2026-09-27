package com.xnotes.format

import com.xnotes.core.text.TextFlow

internal actual object FlowFormat {
    actual fun write(flow: TextFlow): ByteArray = FlowXml.write(flow)
    actual fun readInto(flow: TextFlow, bytes: ByteArray) = FlowXml.readInto(flow, bytes)
}
