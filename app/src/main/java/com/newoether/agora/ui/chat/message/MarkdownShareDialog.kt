package com.newoether.agora.ui.chat.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit tap selection, paged for e-ink. Edits affect the shared copy only. */
@Composable
internal fun MarkdownShareDialog(source:String,onDismiss:()->Unit,onShare:suspend (String)->Unit) {
    val noText=stringResource(R.string.markdown_no_text)
    var blocks by remember(source) { mutableStateOf<List<MarkdownShareBlock>?>(null) }
    var omitted by rememberSaveable(source) { mutableStateOf(arrayListOf<Int>()) }
    var edits by rememberSaveable(source) { mutableStateOf(hashMapOf<Int,String>()) }
    var pageStart by rememberSaveable(source) { mutableIntStateOf(0) }
    var previousStarts by rememberSaveable(source) { mutableStateOf(arrayListOf<Int>()) }
    var pageEnd by remember(source, pageStart) { mutableIntStateOf(pageStart) }
    var editing by remember { mutableStateOf<Int?>(null) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var toShare by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        try { blocks=withContext(Dispatchers.Default){markdownShareBlocks(source)} }
        catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(_:Exception){blocks=listOf(MarkdownShareBlock(0,source.length))}
    }
    LaunchedEffect(toShare) {
        val text=toShare?:return@LaunchedEffect
        sending=true
        try { onShare(text);onDismiss() }
        catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
        catch(e:Exception){error=e.localizedMessage?:"Could not share"}
        finally { sending=false;toShare=null }
    }
    Dialog(onDismissRequest={if(!sending)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.markdown_choose_content),color=Color.Black,style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.markdown_choose_hint),color=Color.Black)
            val items=blocks
            if(items==null)Text(stringResource(R.string.markdown_preparing),color=Color.Black)
            else {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick={omitted=arrayListOf()},enabled=!sending){Text(stringResource(R.string.markdown_select_all),color=Color.Black)}
                    TextButton(onClick={omitted=ArrayList(items.indices.toList())},enabled=!sending){Text(stringResource(R.string.markdown_select_none),color=Color.Black)}
                    Text("${items.size-omitted.size}/${items.size}",color=Color.Black,modifier=Modifier.padding(top=12.dp))
                }
                MarkdownSharePage(pageStart,items.size,Modifier.weight(1f).fillMaxWidth(),
                    onEndChanged={if(pageEnd!=it)pageEnd=it}) { index ->
                        val selected=index !in omitted
                        Surface(Modifier.fillMaxWidth(),color=Color.White,contentColor=Color.Black,border=BorderStroke(if(selected)2.dp else 1.dp,Color.Black)) {
                            Column(Modifier.padding(8.dp)) {
                                Row(Modifier.fillMaxWidth().weight(1f,fill=false).toggleable(value=selected,enabled=!sending,role=Role.Checkbox,onValueChange={checked->omitted=ArrayList(if(checked)omitted-index else omitted+index)})) {
                                    Checkbox(checked=selected,onCheckedChange=null,colors=CheckboxDefaults.colors(checkedColor=Color.Black,uncheckedColor=Color.Black,checkmarkColor=Color.White))
                                    Text(edits[index]?:source.substring(items[index].start,items[index].end),color=Color.Black,overflow=TextOverflow.Ellipsis,fontWeight=if(items[index].headingLevel>0)FontWeight.Bold else FontWeight.Normal,modifier=Modifier.padding(8.dp))
                                }
                                Row {
                                    TextButton(onClick={editing=index},enabled=!sending){Text(stringResource(R.string.markdown_edit_text),color=Color.Black)}
                                    if(items[index].headingLevel>0)TextButton(onClick={
                                        val section=markdownSectionIndices(items,index).toSet()
                                        omitted=ArrayList(if(section.all{it !in omitted})omitted+section else omitted.filter{it !in section})
                                    },enabled=!sending){Text(stringResource(if(markdownSectionIndices(items,index).all{it !in omitted})R.string.markdown_remove_section else R.string.markdown_keep_section),color=Color.Black)}
                                }
                            }
                        }
                }
                error?.let{Text(it,color=Color.Black)}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    OutlinedButton(onClick={pageStart=previousStarts.last();previousStarts=ArrayList(previousStarts.dropLast(1))},enabled=previousStarts.isNotEmpty()&&!sending){Text(stringResource(R.string.markdown_previous),color=Color.Black)}
                    Text("${if(items.isEmpty())0 else pageStart+1}–$pageEnd/${items.size}",color=Color.Black,modifier=Modifier.padding(top=12.dp))
                    OutlinedButton(onClick={previousStarts=ArrayList(previousStarts+pageStart);pageStart=pageEnd},enabled=pageEnd>pageStart&&pageEnd<items.size&&!sending){Text(stringResource(R.string.markdown_next),color=Color.Black)}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(onClick=onDismiss,enabled=!sending){Text(stringResource(R.string.markdown_cancel),color=Color.Black)}
                    Button(onClick={val text=selectedMarkdown(source,items,items.indices.filter{it !in omitted}.toSet(),edits);if(text.isBlank())error=noText else toShare=text},enabled=!sending&&items.indices.any{it !in omitted},colors=ButtonDefaults.buttonColors(containerColor=Color.Black,contentColor=Color.White)) { Text(stringResource(R.string.markdown_share)) }
                }
            }
        }
    }
    editing?.let { index ->
        val items=blocks?:return@let
        var draft by remember(index) { mutableStateOf(edits[index]?:source.substring(items[index].start,items[index].end)) }
        AlertDialog(onDismissRequest={editing=null},title={Text(stringResource(R.string.markdown_edit_text))},text={
            OutlinedTextField(value=draft,onValueChange={draft=it},modifier=Modifier.fillMaxWidth().heightIn(max=360.dp).verticalScroll(rememberScrollState()))
        },confirmButton={TextButton(onClick={edits=HashMap(edits).apply{put(index,draft)};editing=null}){Text(stringResource(R.string.markdown_done))}},dismissButton={TextButton(onClick={editing=null}){Text(stringResource(R.string.markdown_cancel))}})
    }
}
