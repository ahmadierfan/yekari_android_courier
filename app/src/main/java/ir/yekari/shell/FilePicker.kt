package ir.yekari.shell

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * `<input type="file">` وب‌اپ (PhotoUploader، عکس در چت) → دوربین یا گالری.
 *
 * اگر ورودی فقط عکس می‌خواهد و گوشی دوربین دارد، انتخابگر هر دو را پیشنهاد می‌دهد؛
 * با `capture` مستقیم دوربین باز می‌شود. عکس دوربین پیش از تحویل کوچک و درست‌چرخانده
 * می‌شود (۲۰۴۸px) — عکس ۱۲ مگاپیکسلی روی اینترنت موبایل هم کند است هم بی‌فایده.
 */
class FilePicker(private val activity: MainActivity) {
    private var callback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onResult(it.resultCode, it.data) }

    fun open(cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        // وب‌ویو تا جواب قبلی را نگیرد انتخابگر تازه باز نمی‌کند
        callback?.onReceiveValue(null)
        callback = cb

        val types = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val imagesOnly = types.isEmpty() || types.all { it.startsWith("image/") }
        val hasCamera = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

        if (imagesOnly && hasCamera) {
            activity.gate.request(Kind.CAMERA) { granted -> launch(types, params, camera = granted) }
        } else {
            launch(types, params, camera = false)
        }
        return true
    }

    private fun launch(types: List<String>, params: FileChooserParams, camera: Boolean) {
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                types.size == 1 && '/' in types[0] -> types[0]
                types.isNotEmpty() && types.all { it.startsWith("image/") } -> "image/*"
                else -> "*/*"
            }
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.filter { '/' in it }.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
        }
        val capture = if (camera) captureIntent() else null
        val title = activity.getString(R.string.pick_photo)
        val intent = when {
            capture != null && params.isCaptureEnabled -> capture
            capture != null -> Intent.createChooser(pick, title).putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(capture))
            else -> Intent.createChooser(pick, title)
        }
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            deliver(null)
        }
    }

    private fun captureIntent(): Intent? = runCatching {
        val dir = File(activity.cacheDir, CAMERA_DIR).apply { mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        cameraFile = file
        cameraUri = uri
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrNull()

    private fun onResult(code: Int, data: Intent?) {
        val file = cameraFile
        val shot = cameraUri
        if (code != Activity.RESULT_OK) {
            file?.delete()
            return deliver(null)
        }
        val picked = buildList {
            data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { add(it) } }
            if (isEmpty()) data?.data?.let { add(it) }
        }.filter { it != shot }

        if (picked.isEmpty() && file != null && shot != null && file.length() > 0) {
            thread(name = "yekari-shrink") {
                shrink(file)
                activity.runOnUiThread { deliver(arrayOf(shot)) }
            }
            return
        }
        file?.delete()
        deliver(picked.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private fun deliver(uris: Array<Uri>?) {
        callback?.onReceiveValue(uris)
        callback = null
        cameraFile = null
        cameraUri = null
    }

    companion object {
        private const val CAMERA_DIR = "camera"
        private const val MAX_EDGE = 2048
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** عکس‌های دوربینِ روزهای قبل (بعد از آپلود دیگر لازم نیستند) */
        fun cleanup(context: Context) {
            val cutoff = System.currentTimeMillis() - DAY_MS
            File(context.cacheDir, CAMERA_DIR).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }

        /** کوچک‌کردن تا [MAX_EDGE] و اعمال چرخش EXIF؛ هر خطایی → همان فایل اصلی */
        private fun shrink(file: File) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val longest = max(bounds.outWidth, bounds.outHeight)
                if (longest <= 0) return
                var sample = 1
                while (longest / (sample * 2) >= MAX_EDGE) sample *= 2

                val src = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
                val rotation = ExifInterface(file.path).rotationDegrees
                val scale = min(1f, MAX_EDGE.toFloat() / max(src.width, src.height))
                val matrix = Matrix().apply {
                    postScale(scale, scale)
                    postRotate(rotation.toFloat())
                }
                val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (out !== src) out.recycle()
                src.recycle()
            }
        }
    }
}
