package com.xnotes.format

import com.xnotes.core.text.TextFlow

/**
 * The flow text's `flow.xml` entry for the note codec. A bridge while [FlowXml]'s XML parsing still
 * needs javax.xml: on the JVM it is FlowXml itself, native gets it with the XML port.
 */
internal expect object FlowFormat {
    fun write(flow: TextFlow): ByteArray
    fun readInto(flow: TextFlow, bytes: ByteArray)
}

/** The name of the flow text's entry in a note bundle. */
internal const val FLOW_ENTRY_NAME = "flow.xml"
