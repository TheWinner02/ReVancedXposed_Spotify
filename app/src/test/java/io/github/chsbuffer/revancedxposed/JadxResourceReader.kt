package io.github.chsbuffer.revancedxposed

import jadx.api.JadxDecompiler
import jadx.api.plugins.input.data.attributes.JadxAttrType

class JadxResourceReader(val jadx: JadxDecompiler) {
    operator fun get(type: String, name: String): Int {
        val typeClass = jadx.root.appResClass!!.innerClasses.first { it.name == type }
        val field = typeClass.fields.first { it.name == name }
        return field.get(JadxAttrType.CONSTANT_VALUE).value as Int
    }
}