package com.xnotes.format

import com.xnotes.core.text.TextFlow

internal actual object FlowFormat {
    actual fun write(flow: TextFlow): ByteArray = TODO("flow.xml on native comes with the XML port")
    actual fun readInto(flow: TextFlow, bytes: ByteArray): Unit = TODO("flow.xml on native comes with the XML port")
}
