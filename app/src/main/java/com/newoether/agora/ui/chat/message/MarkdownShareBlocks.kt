package com.newoether.agora.ui.chat.message

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Source ranges keep code, escaping and whitespace out of a render-to-text conversion. */
internal data class MarkdownShareBlock(val start:Int,val end:Int,val headingLevel:Int = 0)

internal fun markdownShareBlocks(source:String):List<MarkdownShareBlock> {
    // The parser expects LF. Map normalized offsets back to original bytes/characters.
    val offsets=ArrayList<Int>(source.length+1)
    val normalized=buildString {
        var index=0
        while(index<source.length) {
            offsets.add(index)
            val char=source[index]
            append(if(char=='\r')'\n' else char)
            if(char=='\r' && index+1<source.length && source[index+1]=='\n')index++
            index++
        }
    }
    offsets.add(source.length)
    val root=MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(normalized)
    val nodes=root.children.flatMap { node ->
        val items=node.children.filter { it.type.toString()=="LIST_ITEM" }
        if(items.isEmpty())listOf(node) else items
    }.filter { normalized.substring(it.startOffset,it.endOffset).isNotBlank() }
    return nodes.map { node ->
        val text=normalized.substring(node.startOffset,node.endOffset)
        val heading=Regex("^ {0,3}(#{1,6})\\s").find(text)?.groupValues?.get(1)?.length
            ?: if(node.type.toString()=="SETEXT_1")1 else if(node.type.toString()=="SETEXT_2")2 else 0
        MarkdownShareBlock(offsets[node.startOffset],offsets[node.endOffset],heading)
    }
}

internal fun markdownSectionIndices(blocks:List<MarkdownShareBlock>,index:Int):IntRange {
    val level=blocks[index].headingLevel
    if(level==0)return index..index
    val end=((index+1) until blocks.size).firstOrNull { blocks[it].headingLevel in 1..level } ?: blocks.size
    return index until end
}

internal fun selectedMarkdown(source:String,blocks:List<MarkdownShareBlock>,selected:Set<Int>,edits:Map<Int,String>):String {
    if(selected.size==blocks.size && edits.isEmpty())return source
    return buildString {
        var previous:Int?=null
        blocks.indices.filter { it in selected }.forEach { index ->
            val block=blocks[index]
            previous?.let { before ->
                if(index==before+1)append(source.substring(blocks[before].end,block.start)) else append("\n\n")
            }
            append(edits[index]?:source.substring(block.start,block.end))
            previous=index
        }
    }
}
