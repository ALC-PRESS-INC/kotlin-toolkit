/*
 * Copyright 2018 Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.shared.XmlParser

class Node (val name: String) {

    var children: MutableList<Node> = mutableListOf()
    var attributes: MutableMap<String, String> = mutableMapOf()
    var text: String? = ""

    fun get(name: String) = try {
        children.filter{it.name == name}
    } catch(e: Exception) { null }

    fun getFirst(name: String) = try {
        children.first{it.name == name}
    } catch(e: Exception) { null }

}