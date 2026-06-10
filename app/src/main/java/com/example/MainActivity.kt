package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

// --- UI State Constants ---
sealed interface ApiState {
    object Idle : ApiState
    object Loading : ApiState
    data class Success(val recipes: String) : ApiState
    data class Error(val message: String) : ApiState
}

// --- Sample Basket Model ---
data class SampleBasket(
    val id: String,
    val name: String,
    val description: String,
    val drawableRes: Int,
    val ingredientsPrompt: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_scaffold")
                ) { innerPadding ->
                    ChefIsApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

// --- ViewModel for Clean State Management ---
class ChefIsViewModel : ViewModel() {
    private val _apiState = MutableStateFlow<ApiState>(ApiState.Idle)
    val apiState: StateFlow<ApiState> = _apiState

    // Image can be Uri (from picker/camera) or Drawable resource ID
    var selectedImageUri by mutableStateOf<Uri?>(null)
    var selectedDrawableRes by mutableStateOf<Int?>(null)
    var selectedBitmap by mutableStateOf<Bitmap?>(null)
    
    // Status tracking list of detected ingredients displayed in the app
    var hasSelectedImage by mutableStateOf(false)

    fun resetState() {
        _apiState.value = ApiState.Idle
        selectedImageUri = null
        selectedDrawableRes = null
        selectedBitmap = null
        hasSelectedImage = false
    }

    fun selectSampleBasket(basket: SampleBasket) {
        resetState()
        selectedDrawableRes = basket.drawableRes
        hasSelectedImage = true
    }

    fun selectUri(uri: Uri) {
        resetState()
        selectedImageUri = uri
        hasSelectedImage = true
    }

    fun analyzeIngredients(context: Context) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            _apiState.value = ApiState.Error("API Key is missing. Please configure your GEMINI_API_KEY securely in the AI Studio Secrets panel.")
            return
        }

        _apiState.value = ApiState.Loading

        // Prepare Bitmap in dispatcher threads
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val bitmap = when {
                    selectedBitmap != null -> selectedBitmap
                    selectedDrawableRes != null -> {
                        BitmapFactory.decodeResource(context.resources, selectedDrawableRes!!)
                    }
                    selectedImageUri != null -> {
                        getBitmapFromUri(context, selectedImageUri!!)
                    }
                    else -> null
                }

                if (bitmap == null) {
                    _apiState.value = ApiState.Error("No ingredient image was captured or selected.")
                    return@launch
                }

                // Compress bitmap scale to around max 800px to speed up API transfer
                val scaledBitmap = scaleBitmapDown(bitmap, 800)
                val base64Data = scaledBitmap.toBase64()

                // Trigger Gemini Vision Model API Call
                val response = callGeminiVisionApi(apiKey, base64Data)
                _apiState.value = ApiState.Success(response)
            } catch (e: Exception) {
                Log.e("ChefIsViewModel", "Evaluation failed", e)
                _apiState.value = ApiState.Error(e.message ?: "An unexpected error occurred while recommending recipes.")
            }
        }
    }

    private fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        var newWidth = originalWidth
        var newHeight = originalHeight

        if (originalWidth > originalHeight) {
            if (originalWidth > maxDimension) {
                newWidth = maxDimension
                newHeight = (newWidth * originalHeight) / originalWidth
            }
        } else {
            if (originalHeight > maxDimension) {
                newHeight = maxDimension
                newWidth = (newHeight * originalWidth) / originalHeight
            }
        }
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun Bitmap.toBase64(): String {
        val outputStream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private suspend fun callGeminiVisionApi(apiKey: String, base64Image: String): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val systemPrompt = """
            You are "Chef Is", an expert Michelin-starred chef dedicated to organic, healthy, creative, and sustainable cuisine. 
            Analyze the image of materials or ingredients provided.
            1. Identify every fruit, vegetable, or ingredient that you can spot.
            2. Suggest 2-3 healthy, organic-centered, delicious recipes that can be made with these primary base ingredients. It is fine if some secondary pantry staples (like salt, pepper, olive oil, or water) must be added.
            3. Structure your response with gorgeous Markdown styling. Follow this exact format:
            
            # 🌱 DETECTED ORGANIC MATERIALS
            * List the ingredients identified in the photo here with a short remark on their freshness or culinary potential.
            
            ---
            
            # 🥣 RECOMMENDATION 1: [Recipe Name]
            * **Prep Time:** [X mins] | **Cook Time:** [Y mins]
            * **Aesthetic Vibe:** [Short poetic sentence about the recipe's style]
            
            ### 📝 Key Ingredients
            - [List out specific organic items and amounts]
            
            ### 👩‍🍳 Craft Steps
            1. [Detailed cooking instruction step]
            2. [Detailed cooking instruction step]
            
            ---
            
            # 🥣 RECOMMENDATION 2: [Recipe Name]
            * **Prep Time:** [X mins] | **Cook Time:** [Y mins]
            * **Aesthetic Vibe:** [Vibe description]
            
            ### 📝 Key Ingredients
            - [List components]
            
            ### 👩‍🍳 Craft Steps
            1. [Step 1]
            2. [Step 2]
            
            Keep your language extremely engaging, professional, and centering on natural, wholesome, organic cooking methods!
        """.trimIndent()

        // Build the payload safely using org.json
        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = requestJson.toString().toRequestBody(mediaType)
        
        // We use gemini-3.5-flash which is the standard, state-of-the-art vision & reasoning model
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown API response stream error."
                val errorJson = try { JSONObject(errorBody) } catch(e: Exception) { null }
                val readableMessage = errorJson?.getJSONArray("error")?.let { errorJson.getJSONObject("error").getString("message") } 
                    ?: "Gemini API rejected request (HTTP Code ${response.code})."
                throw Exception(readableMessage)
            }

            val bodyString = response.body?.string() ?: throw Exception("Received empty response from the chef assistant.")
            val parsedJson = JSONObject(bodyString)
            val candidates = parsedJson.getJSONArray("candidates")
            if (candidates.length() == 0) throw Exception("The Chef couldn't extract any ideas from this visual.")
            
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")
            if (parts.length() == 0) throw Exception("Empty chef response.")
            
            parts.getJSONObject(0).getString("text")
        }
    }
}

@Composable
fun ChefIsApp(
    modifier: Modifier = Modifier,
    viewModel: ChefIsViewModel = viewModel()
) {
    val context = LocalContext.current
    val apiState by viewModel.apiState.collectAsState()

    // Setup Local Camera Capture Temporary Target Uri
    val tempFileUri = remember {
        val tempFile = File(context.cacheDir, "chef_is_capture.jpg").apply {
            if (exists()) delete()
            createNewFile()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
    }

    // Photo Capture Contract Launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            viewModel.selectUri(tempFileUri)
            Toast.makeText(context, "Captured garden photo successfully!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No photo captured.", Toast.LENGTH_SHORT).show()
        }
    }

    // Photo Gallery Selector Activity Contract
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.selectUri(uri)
        }
    }

    // List of predefined awesome organic agricultural arrays for effortless immediate testing!
    val sampleBaskets = remember {
        listOf(
            SampleBasket(
                id = "green",
                name = "Fresh Green Harvest",
                description = "Avocados, organic spinach, crisp cucumbers & key limes",
                drawableRes = com.example.R.drawable.basket_green,
                ingredientsPrompt = "Avocados, organic spinach, organic green culinary cucumbers, fresh key limes, parsley"
            ),
            SampleBasket(
                id = "citrus",
                name = "Citrus Sunrise orchard",
                description = "Vibrant oranges, sweet strawberries, bananas & root ginger",
                drawableRes = com.example.R.drawable.basket_citrus,
                ingredientsPrompt = "Oranges, ripe red organic garden strawberries, organic sweet yellow bananas, organic ginger root"
            ),
            SampleBasket(
                id = "root",
                name = "Rustic Root Farm",
                description = "Organic sweet potato, garden carrots, red onion & fresh herbs",
                drawableRes = com.example.R.drawable.basket_root,
                ingredientsPrompt = "Organic sweet potatoes, fresh garden bunch carrots, rosemary twig, red garlic bulb, pungent red onions"
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(com.example.ui.theme.NaturalBgEggshell)
    ) {
        // --- 1. Distinctive Natural Tones Primary Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 12.dp)
                .testTag("natural_header"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Welcome to",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.5.sp,
                    color = com.example.ui.theme.NaturalTextSecondary,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Text(
                    text = "Chef Is",
                    fontSize = 32.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Normal,
                    color = com.example.ui.theme.NaturalTextTitle
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(com.example.ui.theme.NaturalPaleHerbal)
                    .border(1.dp, com.example.ui.theme.NaturalHerbalBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccountCircle,
                    contentDescription = "User account",
                    tint = com.example.ui.theme.NaturalNavActiveText,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Parent LazyColumn to scroll smoothly across different UI regions
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // --- 2. Dynamic Visual Hero / Empty State Area ---
            item {
                AnimatedVisibility(
                    visible = !viewModel.hasSelectedImage,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(32.dp))
                                .background(com.example.ui.theme.NaturalCreamSurface)
                                .border(
                                    border = BorderStroke(2.dp, com.example.ui.theme.NaturalHerbalBorder),
                                    shape = RoundedCornerShape(32.dp)
                                )
                                .clickable {
                                    galleryLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                .testTag("hero_visualization"),
                            contentAlignment = Alignment.Center
                        ) {
                            // Subtly styled background grid pattern or accents can go here
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .shadow(1.dp, CircleShape)
                                        .background(com.example.ui.theme.NaturalBgEggshell, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.RestaurantMenu,
                                        contentDescription = "Pantry menu icon",
                                        tint = com.example.ui.theme.NaturalForestGreen,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "What's in your pantry?",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = com.example.ui.theme.NaturalTextPrimary,
                                    fontFamily = FontFamily.Serif
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Capture your ingredients to unlock personalized recipe secrets.",
                                    fontSize = 14.sp,
                                    color = com.example.ui.theme.NaturalTextSecondary,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp,
                                    modifier = Modifier.widthIn(max = 240.dp)
                                )
                            }
                        }
                    }
                }
            }

            // --- 3. Selected Workspace Display ---
            item {
                AnimatedVisibility(
                    visible = viewModel.hasSelectedImage,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "Selected Organic Palette",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = com.example.ui.theme.NaturalTextPrimary,
                            fontFamily = FontFamily.Serif,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(Color.White)
                                .border(1.dp, com.example.ui.theme.NaturalHerbalBorder, RoundedCornerShape(32.dp))
                                .shadow(1.dp, RoundedCornerShape(32.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (viewModel.selectedDrawableRes != null) {
                                Image(
                                    painter = painterResource(id = viewModel.selectedDrawableRes!!),
                                    contentDescription = "Selected Preloaded Sample Basket Image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else if (viewModel.selectedImageUri != null) {
                                AsyncImage(
                                    model = viewModel.selectedImageUri,
                                    contentDescription = "Selected User Ingredient Image",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }

                            // Interactive overlays
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f))
                                        )
                                    )
                            )

                            // Clear Selection Float Button
                            IconButton(
                                onClick = { viewModel.resetState() },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .background(Color.White.copy(alpha = 0.9f), CircleShape)
                                    .size(36.dp)
                                    .testTag("clear_image_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear selected selection",
                                    tint = com.example.ui.theme.NaturalTextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Quick Tag Indicator
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp)
                                    .background(com.example.ui.theme.NaturalForestGreen, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Done,
                                    contentDescription = "Confirmed",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Ready to Analyze",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Glow Trigger Command Button to run the Gemini Analysis
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.analyzeIngredients(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .shadow(2.dp, RoundedCornerShape(28.dp))
                                .testTag("recommend_recipes_button"),
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = com.example.ui.theme.NaturalForestGreen
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Recommend Recipes Magic"
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Recommend Sustainable Recipes",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            // --- 4. Main Actions Area (Stacked Pill Buttons) ---
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    // Click Photo Button (Forest Green Color with custom styling details)
                    Button(
                        onClick = { cameraLauncher.launch(tempFileUri) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = com.example.ui.theme.NaturalForestGreen
                        ),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .shadow(2.dp, RoundedCornerShape(28.dp))
                            .testTag("click_photo_card"),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color.White.copy(alpha = 0.20f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PhotoCamera,
                                    contentDescription = "Camera",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Click an image",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Instant detection via camera",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Go",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Upload Photo Button (Pale herbal with green border details)
                    OutlinedButton(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = com.example.ui.theme.NaturalPaleHerbal
                        ),
                        shape = RoundedCornerShape(28.dp),
                        border = BorderStroke(1.dp, com.example.ui.theme.NaturalHerbalBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .shadow(1.dp, RoundedCornerShape(28.dp))
                            .testTag("upload_photo_card"),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(com.example.ui.theme.NaturalForestGreen.copy(alpha = 0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Image,
                                    contentDescription = "Gallery",
                                    tint = com.example.ui.theme.NaturalForestGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Upload an image",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = com.example.ui.theme.NaturalTextPrimary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Select from your gallery",
                                    fontSize = 12.sp,
                                    color = com.example.ui.theme.NaturalTextSecondary
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Go",
                                tint = com.example.ui.theme.NaturalTextSecondary
                            )
                        }
                    }
                }
            }

            // --- 5. Recently Found Context ---
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp, vertical = 8.dp)
                        .testTag("recent_context"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(com.example.ui.theme.NaturalSageAccent, CircleShape)
                        )
                        Text(
                            text = "RECENTLY FOUND",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = com.example.ui.theme.NaturalTextSecondary
                        )
                    }
                    Text(
                        text = "Avocado, Kale, Lemon",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = com.example.ui.theme.NaturalTextSecondary
                    )
                }
            }

            // --- 6. Pre-loaded Organic Sample Baskets ---
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, bottom = 8.dp)
                ) {
                    Text(
                        text = "Or Try Preloaded Organic Baskets",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = com.example.ui.theme.NaturalTextTitle,
                        fontFamily = FontFamily.Serif,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp)
                    )
                    Text(
                        text = "Perfect for testing culinary recommends instantly",
                        fontSize = 12.sp,
                        color = com.example.ui.theme.NaturalTextSecondary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                    )

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(sampleBaskets) { basket ->
                            val isSelected = viewModel.selectedDrawableRes == basket.drawableRes
                            
                            Card(
                                modifier = Modifier
                                    .width(260.dp)
                                    .clickable { viewModel.selectSampleBasket(basket) }
                                    .shadow(1.dp, RoundedCornerShape(16.dp))
                                    .testTag("sample_basket_${basket.id}"),
                                shape = RoundedCornerShape(16.dp),
                                border = if (isSelected) BorderStroke(2.dp, com.example.ui.theme.NaturalForestGreen) else BorderStroke(1.dp, com.example.ui.theme.NaturalHerbalBorder.copy(alpha = 0.5f)),
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(130.dp)
                                    ) {
                                        Image(
                                            painter = painterResource(id = basket.drawableRes),
                                            contentDescription = basket.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f))
                                                    )
                                                )
                                        )
                                        Text(
                                            text = basket.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Serif,
                                            fontSize = 15.sp,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(12.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = basket.description,
                                            fontSize = 12.sp,
                                            color = com.example.ui.theme.NaturalTextSecondary,
                                            lineHeight = 16.sp,
                                            minLines = 2
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "Select & Suggest",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = com.example.ui.theme.NaturalForestGreen
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "Tap to choose",
                                                tint = com.example.ui.theme.NaturalSageAccent,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- 7. Interactive Results and Suggestions Panel ---
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    when (val state = apiState) {
                        is ApiState.Idle -> {
                            // Render a beautiful Natural instructions card if a photo is selected but not analyzed yet
                            if (viewModel.hasSelectedImage) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color.White),
                                    border = BorderStroke(1.dp, com.example.ui.theme.NaturalHerbalBorder)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = "Ready guide",
                                            tint = com.example.ui.theme.NaturalSageAccent,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Tap 'Recommend Sustainable Recipes' above to let the Chef craft your organic meal card!",
                                            fontSize = 12.sp,
                                            color = com.example.ui.theme.NaturalTextSecondary,
                                            textAlign = TextAlign.Center,
                                            lineHeight = 18.sp
                                        )
                                    }
                                }
                            }
                        }

                        is ApiState.Loading -> {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)
                                    .testTag("loading_card"),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, com.example.ui.theme.NaturalHerbalBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Slicing animation placeholder
                                    LoadingSpinnerIndicator()
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Chef Is Culinary Lab",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = com.example.ui.theme.NaturalForestGreen,
                                        fontFamily = FontFamily.Serif
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Evaluating ingredients under the light and harvesting healthy, sustainable recipe cards...",
                                        fontSize = 13.sp,
                                        color = com.example.ui.theme.NaturalTextSecondary,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }

                        is ApiState.Success -> {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)
                                    .testTag("recipe_results_card"),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                border = BorderStroke(1.dp, com.example.ui.theme.NaturalHerbalBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(bottom = 14.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MenuBook,
                                            contentDescription = "Success Book",
                                            tint = com.example.ui.theme.NaturalForestGreen,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Chef Recommendations",
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = com.example.ui.theme.NaturalTextTitle,
                                            fontFamily = FontFamily.Serif
                                        )
                                    }

                                    // Display highly polished and styled markdown recipe cards!
                                    RecipeContentRender(markdownContent = state.recipes)
                                    
                                    Spacer(modifier = Modifier.height(18.dp))
                                    OutlinedButton(
                                        onClick = { viewModel.resetState() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = com.example.ui.theme.NaturalForestGreen
                                        ),
                                        shape = RoundedCornerShape(24.dp),
                                        border = BorderStroke(1.dp, com.example.ui.theme.NaturalForestGreen)
                                    ) {
                                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry")
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Scan Another Palette")
                                    }
                                }
                            }
                        }

                        is ApiState.Error -> {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)
                                    .testTag("error_card"),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFFDF2F2)),
                                border = BorderStroke(1.dp, Color(0xFFF8D7DA))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(20.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.ErrorOutline,
                                            contentDescription = "Error icon",
                                            tint = Color(0xFFD32F2F),
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Scanning Interrupted",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = Color(0xFF721C24)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = state.message,
                                        fontSize = 13.sp,
                                        color = Color(0xFF721C24),
                                        lineHeight = 18.sp
                                    )
                                    
                                    if (state.message.contains("API Key")) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.White, RoundedCornerShape(10.dp))
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                text = "To make calls to the Gemini API, satisfy the prototype environment setup by adding your API key in the AI Studio SECRETS panel on the left/top sidebar.",
                                                fontSize = 11.sp,
                                                color = com.example.ui.theme.NaturalTextSecondary
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Button(
                                        onClick = { viewModel.resetState() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Text("Back to Canvas", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 8. Premium Anchored Bottom Navigation Bar (Matched HTML Specs) ---
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)),
            color = com.example.ui.theme.NaturalBottomNavBg,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Home Nav Button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.clickable {
                        Toast.makeText(context, "Already in organic pantry home", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(com.example.ui.theme.NaturalPaleHerbal)
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Home",
                            tint = com.example.ui.theme.NaturalForestGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Home",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = com.example.ui.theme.NaturalForestGreen
                    )
                }

                // Recipes Nav Button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .alpha(0.7f)
                        .clickable {
                            Toast.makeText(context, "Scanning starts recipe cards automatically!", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MenuBook,
                        contentDescription = "Recipes",
                        tint = com.example.ui.theme.NaturalTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Recipes",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = com.example.ui.theme.NaturalTextSecondary
                    )
                }

                // Saved Nav Button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .alpha(0.7f)
                        .clickable {
                            Toast.makeText(context, "Saved recipes features coming soon!", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = "Saved",
                        tint = com.example.ui.theme.NaturalTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Saved",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = com.example.ui.theme.NaturalTextSecondary
                    )
                }

                // Settings Nav Button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .alpha(0.7f)
                        .clickable {
                            Toast.makeText(context, "Settings is already configured with Gemini API keys!", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "Settings",
                        tint = com.example.ui.theme.NaturalTextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Settings",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = com.example.ui.theme.NaturalTextSecondary
                    )
                }
            }
        }
    }

}

// --- High-fidelity Animated Material Loading Spinner ---
@Composable
fun LoadingSpinnerIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Icon(
        imageVector = Icons.Default.Spa,
        contentDescription = "Organic Loading Windmill",
        tint = Color(0xFF558B2F),
        modifier = Modifier
            .size(54.dp)
            .rotate(rotationAngle)
    )
}

// --- Styled Interactive Action Card ---
@Composable
fun InteractiveActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeText: String,
    backgroundColor: Color,
    borderColor: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(140.dp)
            .clickable { onClick() }
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = badgeText,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color(0xFF2E7D32), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1B3B1E),
                    fontFamily = FontFamily.Serif
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Color(0xFF555555)
                )
            }
        }
    }
}

// --- Scale Animation Helper ---
@Composable
fun Modifier.scaleAnimation(): Modifier {
    val infiniteTransition = rememberInfiniteTransition()
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    return this.shadow(elevation = (scale * 2).dp, shape = CircleShape)
}

// --- Elegant styled markdown custom parser for professional culinary cards ---
@Composable
fun RecipeContentRender(markdownContent: String) {
    val lines = markdownContent.split("\n")
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        lines.forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("# ") -> {
                    // Title Header
                    Text(
                        text = trimmed.removePrefix("# "),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32),
                        fontFamily = FontFamily.Serif,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
                    )
                }
                trimmed.startsWith("## ") || trimmed.startsWith("### ") -> {
                    // Subheaders (Key ingredients, Cooking steps)
                    val title = trimmed.removePrefix("## ").removePrefix("# ")
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF558B2F),
                        fontFamily = FontFamily.Serif,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                trimmed.startsWith("* **Prep Time:**") || trimmed.startsWith("**Prep Time:**") -> {
                    // Stats / Meta line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF1F8E9), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = trimmed.replace("*", "").trim(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                }
                trimmed.startsWith("* ") || trimmed.startsWith("- ") -> {
                    // List item
                    val itemText = trimmed.removePrefix("* ").removePrefix("- ")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = itemText,
                            fontSize = 13.sp,
                            color = Color(0xFF333333),
                            lineHeight = 18.sp
                        )
                    }
                }
                trimmed.firstOrNull()?.isDigit() == true && trimmed.contains(". ") -> {
                    // Numbered cooking steps
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        val numAndText = trimmed.split(". ", limit = 2)
                        val num = numAndText.getOrNull(0) ?: ""
                        val content = numAndText.getOrNull(1) ?: ""
                        
                        Text(
                            text = "$num.",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = content,
                            fontSize = 13.sp,
                            color = Color(0xFF333333),
                            lineHeight = 18.sp
                        )
                    }
                }
                trimmed.isNotEmpty() -> {
                    Text(
                        text = trimmed,
                        fontSize = 13.sp,
                        color = Color(0xFF444444),
                        lineHeight = 18.sp,
                        fontStyle = if (trimmed.startsWith("*") && trimmed.endsWith("*")) androidx.compose.ui.text.font.FontStyle.Italic else null
                    )
                }
            }
        }
    }
}
