package com.kyhsgeekcode.disassembler.files

import at.pollaknet.api.facile.Facile
import at.pollaknet.api.facile.exception.CoffPeDataNotFoundException
import at.pollaknet.api.facile.exception.SizeMismatchException
import at.pollaknet.api.facile.exception.UnexpectedHeaderDataException
import com.kyhsgeekcode.disassembler.*
import nl.lxtreme.binutils.elf.MachineType
import splitties.init.appCtx
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.io.IOException

// represents a raw file and interface
abstract class AbstractFile : Closeable {

    @Throws(IOException::class)
    override fun close() {
        return
    }

    override fun toString(): String {
        if (fileContents == null) {
            return "The file has not been configured. You should setup manually in the first page before you can see the details."
        }
        val builder = StringBuilder(
            if (this is RawFile) "The file has not been configured. You should setup manually in the first page before you can see the details." +
                    System.lineSeparator() else ""
        )
        builder.append(/*R.getString(R.string.FileSize)*/"File Size:")
            .append(Integer.toHexString(fileContents.size))
            .append(ls)
        builder.append(appCtx.getString(R.string.FoffsCS))
            .append(java.lang.Long.toHexString(codeSectionBase))
            .append(ls)
        builder.append(appCtx.getString(R.string.FoffsCSEd))
            .append(java.lang.Long.toHexString(codeSectionLimit))
            .append(ls)
        builder.append(appCtx.getString(R.string.FoffsEP))
            .append(java.lang.Long.toHexString(codeSectionBase + entryPoint))
            .append(ls)
        builder.append(appCtx.getString(R.string.VAofCS))
            .append(java.lang.Long.toHexString(codeVirtAddr))
            .append(ls)
        builder.append(appCtx.getString(R.string.VAofCSE))
            .append(java.lang.Long.toHexString(codeSectionLimit + codeVirtAddr))
            .append(ls)
        builder.append(appCtx.getString(R.string.VAofEP))
            .append(java.lang.Long.toHexString(entryPoint + codeVirtAddr))
        return builder.toString()
    }

    // 	public AbstractFile(File file) throws IOException
// 	{
//
// 	}
// 	public AbstractFile(FileChannel channel)
// 	{
//
// 	}
    @JvmField
    val ls = System.lineSeparator()
    open var codeSectionBase: Long = 0
    open var codeSectionLimit: Long = 0

    val exportSymbols: MutableList<Symbol> = ArrayList()
    val importSymbols: MutableList<ImportSymbol> = ArrayList()
    lateinit var fileContents: ByteArray
    open var entryPoint: Long = 0
    open var codeVirtAddr: Long = 0
    open var machineType: MachineType = MachineType.AARCH64

    @JvmField
    var path = ""

    companion object {
        private const val TAG = "AbstractFile"

        @JvmStatic
        @Throws(IOException::class)
        fun createInstance(file: File): AbstractFile {
            // file??? ????????? mainactivity??? ????????? ??? ???????????? AbstractFile??? ?????????.
            // FacileAPI?????? ????????? ?????? ????????? ????????? ?????? ??????.
            // ?????? ????????? ?????????????????? ?????? ??? ????????? ????????? ?????? ????????? ????????????.
            // AfterReadFully() ????????????!
            // ??? ??????
            // AfterReadFully ???????????? AbstractFile??? ????????? ????????????.
            // ????????? AfterReadFully ????????? ??????????????? ?????????!
            // ????????? ??????????????? ?????????
            // ????????????
            val content = file.readBytes()
            if (file.path.endsWith("assets/bin/Data/Managed/Assembly-CSharp.dll")) { // Unity C# dll file
                Logger.v(TAG, "Found C# unity dll")
                try {
                    val facileReflector = Facile.load(file.path)
                    // load the assembly
                    val assembly = facileReflector.loadAssembly()
                    if (assembly != null) {
                        Logger.v(TAG, assembly.toExtendedString())
                        return ILAssmebly(facileReflector)
                    } else {
                        println("File maybe contains only resources...")
                    }
                } catch (e: CoffPeDataNotFoundException) {
                    Logger.e(TAG, "", e)
                } catch (e: UnexpectedHeaderDataException) {
                    e.printStackTrace()
                } catch (e: SizeMismatchException) {
                    e.printStackTrace()
                }
            } else {
                return try {
                    ElfFile(file, content)
                } catch (e: Exception) { // not an elf file. try PE parser
                    Timber.d(e, "Fail elfutil")
                    try {
                        PEFile(file, content)
                    } catch (f: NotThisFormatException) {
                        Timber.e(f, "Not this format exception")
                        RawFile(file, content)
                        // AllowRawSetup();
// failed to parse the file. please setup manually.
                    } catch (f: RuntimeException) { // AlertError("Failed to parse the file. Please setup manually. Sending an error report, the file being analyzed can be attached.", f);
                        Timber.e(f, "Not this format exception")
                        RawFile(file, content)
                        // AllowRawSetup();
                    } catch (g: Exception) { // AlertError("Unexpected exception: failed to parse the file. please setup manually.", g);
                        Timber.e(g, "What the exception")
                        RawFile(file, content)
                        // AllowRawSetup();
                    }
                }
            }
            return RawFile(file, content)
//            return null
        }
    }
}
