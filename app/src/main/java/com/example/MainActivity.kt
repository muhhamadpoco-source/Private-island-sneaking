package com.example

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.*
import kotlin.random.Random

object SoundEffects {
    private var toneGen: ToneGenerator? = null
    fun playBlip() {
        try {
            if (toneGen == null) toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (e: Exception) { }
    }
    fun playFail() {
        try {
            if (toneGen == null) toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen?.startTone(ToneGenerator.TONE_CDMA_SOFT_ERROR_LITE, 200)
        } catch (e: Exception) { }
    }
}

fun formatTime(minutes: Int): String {
    val h = (minutes / 60) % 24
    val m = minutes % 60
    val period = if (h < 12) "AM" else "PM"
    val displayH = if (h == 0) 12 else if (h > 12) h - 12 else h
    return String.format("%d:%02d %s", displayH, m, period)
}

data class GameObject(val rect: Rect, val type: String, val id: Int = 0)

data class Guard(var x: Float, var y: Float, var patrolDirX: Float, var patrolDirY: Float)

enum class GamePhase { WORLD, INSIDE_HOUSE, HACKING, GAME_OVER, VICTORY, MENU }

data class GameState(
    val phase: GamePhase = GamePhase.MENU,
    val playerX: Float = 200f,
    val playerY: Float = 1000f,
    val timeMinutes: Float = 3 * 60 + 13f,
    val stamina: Float = 100f,
    val inKayak: Boolean = true,
    val houses: List<GameObject> = emptyList(),
    val mansion: GameObject = GameObject(Rect(0f,0f,0f,0f), "mansion"),
    val guards: List<Guard> = emptyList(),
    val serversHacked: Int = 0,
    val serverHouseIds: List<Int> = emptyList(),
    val currentHouseId: Int? = null,
    val deathReason: String = "",
    val hackingTarget: String = "ssh root@192.168.1.5",
    val hackingInput: String = "",
    val hackingTime: Float = 15f,
    val foundShampoo: Boolean = false,
    val foundSoap: Boolean = false,
    val isSleeping: Boolean = false,
    val graphicsSetting: String = "Realistic"
)

class GameViewModel : ViewModel() {
    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state

    init {
        generateWorld()
    }

    private fun generateWorld() {
        val houses = mutableListOf<GameObject>()
        val guards = mutableListOf<Guard>()
        val rand = java.util.Random()
        
        val mansion = GameObject(Rect(1800f, 1800f, 2200f, 2200f), "mansion")
        
        for (i in 1..40) {
            var hx: Float
            var hy: Float
            var valid = false
            while (!valid) {
                hx = 1000f + rand.nextFloat() * 2000f
                hy = 1000f + rand.nextFloat() * 2000f
                val dist = sqrt((hx - 2000f).pow(2) + (hy - 2000f).pow(2))
                if (dist < 1300f && dist > 300f) {
                    valid = true
                    houses.add(GameObject(Rect(hx, hy, hx + 80f, hy + 80f), "house", i))
                }
            }
        }
        
        for (i in 1..30) {
            val gx = 1000f + rand.nextFloat() * 2000f
            val gy = 1000f + rand.nextFloat() * 2000f
            guards.add(Guard(gx, gy, if(rand.nextBoolean()) 1f else -1f, if(rand.nextBoolean()) 1f else -1f))
        }

        val serverHouseIds = houses.shuffled().take(3).map { it.id }

        _state.value = GameState(
            houses = houses,
            mansion = mansion,
            guards = guards,
            serverHouseIds = serverHouseIds,
            playerX = 500f,
            playerY = 2000f,
            phase = GamePhase.MENU
        )
    }

    fun startGame() {
        _state.update { it.copy(phase = GamePhase.WORLD, timeMinutes = 193f) }
    }

    fun updateJoystick(dx: Float, dy: Float) {
        if (_state.value.phase != GamePhase.WORLD && _state.value.phase != GamePhase.INSIDE_HOUSE) return
        
        val s = _state.value
        if (s.isSleeping) return
        
        val speed = if (s.inKayak) 10f else 14f
        val newX = s.playerX + dx * speed
        val newY = s.playerY + dy * speed
        
        if (s.phase == GamePhase.WORLD) {
            val distFromCenter = sqrt((newX - 2000f).pow(2) + (newY - 2000f).pow(2))
            val onIsland = distFromCenter < 1450f
            
            var newStamina = s.stamina
            if (!onIsland) {
                if (!s.inKayak) _state.update { it.copy(inKayak = true) }
                if (dx != 0f || dy != 0f) newStamina -= 0.1f
                else newStamina += 0.5f
                
                if (newStamina <= 0) {
                    die("You drowned because your stamina depleted while kayaking.")
                    return
                }
            } else {
                if (s.inKayak) _state.update { it.copy(inKayak = false) }
                newStamina += 0.5f
            }
            newStamina = newStamina.coerceIn(0f, 100f)

            var collidedHouse: GameObject? = null
            for (h in s.houses) {
                if (h.rect.contains(Offset(newX, newY))) {
                    collidedHouse = h
                    break
                }
            }
            
            if (s.mansion.rect.contains(Offset(newX, newY))) {
                die("You stumbled into the main mansion! Heavy security shot you instantly.")
                return
            }

            if (collidedHouse != null) {
                enterHouse(collidedHouse.id)
                return
            }
            
            val newTime = s.timeMinutes + 0.05f

            _state.update { 
                it.copy(
                    playerX = newX, 
                    playerY = newY, 
                    stamina = newStamina,
                    timeMinutes = newTime
                ) 
            }
        } else if (s.phase == GamePhase.INSIDE_HOUSE) {
            val ix = newX.coerceIn(50f, 750f)
            val iy = newY.coerceIn(50f, 750f)
            
            val newTime = s.timeMinutes + 0.05f
            _state.update { it.copy(playerX = ix, playerY = iy, timeMinutes = newTime) }
            
            if (iy > 740f && ix in 350f..450f) {
                exitHouse()
            }
        }
        checkTime()
    }
    
    fun interact() {
        val s = _state.value
        if (s.phase == GamePhase.INSIDE_HOUSE) {
            val px = s.playerX
            val py = s.playerY
            
            if (px in 50f..350f && py in 50f..350f) {
                if (s.timeMinutes > 300f && s.serversHacked == 3) {
                    sleep()
                }
            }
            if (px in 450f..750f && py in 50f..250f) {
                if (s.currentHouseId in s.serverHouseIds) {
                    startHacking()
                }
            }
            if (px in 500f..750f && py in 500f..750f) {
                if (!s.foundShampoo) _state.update { it.copy(foundShampoo = true) }
                else if (!s.foundSoap) _state.update { it.copy(foundSoap = true) }
            }
        }
    }

    private fun sleep() {
        _state.update { it.copy(isSleeping = true, timeMinutes = 7 * 60 + 30f) }
    }

    fun wakeUp() {
        _state.update { it.copy(isSleeping = false) }
    }

    fun updateGuards() {
        if (_state.value.phase != GamePhase.WORLD) return
        val s = _state.value
        val newGuards = s.guards.map { g ->
            var nx = g.x + g.patrolDirX * 4f
            var ny = g.y + g.patrolDirY * 4f
            if (Random.nextFloat() < 0.02f) {
                nx = g.x
                ny = g.y
                g.patrolDirX = (Random.nextFloat() - 0.5f) * 2f
                g.patrolDirY = (Random.nextFloat() - 0.5f) * 2f
            }
            val dist = sqrt((nx - s.playerX).pow(2) + (ny - s.playerY).pow(2))
            if (dist < 40f && !s.inKayak) {
                die("A security guard spotted you! Shot with a silencer.")
            }
            Guard(nx, ny, g.patrolDirX, g.patrolDirY)
        }
        _state.update { it.copy(guards = newGuards) }
    }

    private fun enterHouse(id: Int) {
        _state.update { 
            it.copy(
                phase = GamePhase.INSIDE_HOUSE,
                currentHouseId = id,
                playerX = 400f,
                playerY = 700f
            )
        }
    }

    private fun exitHouse() {
        val h = _state.value.houses.find { it.id == _state.value.currentHouseId }
        val outX = h?.rect?.left ?: 2000f
        val outY = (h?.rect?.bottom ?: 2000f) + 50f
        _state.update { 
            it.copy(
                phase = GamePhase.WORLD,
                currentHouseId = null,
                playerX = outX,
                playerY = outY
            )
        }
    }

    private fun startHacking() {
        val cmds = listOf("ssh admin@server", "./extract_data.sh", "scp -r /secret .", "netcat -lvnp 4444")
        _state.update { 
            it.copy(
                phase = GamePhase.HACKING,
                hackingTarget = cmds.random(),
                hackingInput = "",
                hackingTime = 15f
            )
        }
    }

    fun submitHacking(input: String) {
        _state.update { it.copy(hackingInput = input) }
        if (input == _state.value.hackingTarget) {
            val h = _state.value.serversHacked + 1
            _state.update { it.copy(phase = GamePhase.INSIDE_HOUSE, serversHacked = h) }
        }
    }

    fun tickHackingTime() {
        if (_state.value.phase == GamePhase.HACKING) {
            val t = _state.value.hackingTime - 0.1f
            if (t <= 0) {
                die("Failed to hack in time! Security detected your location.")
            } else {
                _state.update { it.copy(hackingTime = t) }
            }
        }
    }

    private fun checkTime() {
        val s = _state.value
        if (s.timeMinutes >= 360f && s.timeMinutes < 420f && s.phase == GamePhase.WORLD) {
            die("The hurricane hit at 6:00 AM while you were outside! You were swept away.")
        }
        if (s.timeMinutes >= 480f) {
            if (s.serversHacked == 3 && s.inKayak && sqrt((s.playerX - 2000f).pow(2) + (s.playerY - 2000f).pow(2)) > 1600f) {
                _state.update { it.copy(phase = GamePhase.VICTORY) }
            } else {
                die("The hurricane resumed at 8:00 AM. You didn't escape in time.")
            }
        }
        
        if (s.timeMinutes >= 450f && s.serversHacked >= 3 && s.inKayak) {
            val dist = sqrt((s.playerX - 2000f).pow(2) + (s.playerY - 2000f).pow(2))
            if (dist > 1800f) {
                _state.update { it.copy(phase = GamePhase.VICTORY) }
            }
        }
    }

    private fun die(reason: String) {
        _state.update { it.copy(phase = GamePhase.GAME_OVER, deathReason = reason) }
        SoundEffects.playFail()
    }

    fun restart() {
        generateWorld()
    }
    
    fun setGraphics(setting: String) {
        _state.update { it.copy(graphicsSetting = setting) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    GameApp()
                }
            }
        }
    }
}

@Composable
fun GameApp(viewModel: GameViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    
    var joyX by remember { mutableFloatStateOf(0f) }
    var joyY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.phase) {
        while (state.phase == GamePhase.WORLD || state.phase == GamePhase.INSIDE_HOUSE) {
            viewModel.updateJoystick(joyX, joyY)
            viewModel.updateGuards()
            delay(32L)
        }
    }
    
    LaunchedEffect(state.phase) {
        while(state.phase == GamePhase.HACKING) {
            delay(100L)
            viewModel.tickHackingTime()
        }
    }

    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        when (state.phase) {
            GamePhase.MENU -> MainMenuScreen(state, viewModel)
            GamePhase.WORLD -> {
                WorldRenderer(state)
                if (state.graphicsSetting == "Realistic") {
                    val rainIntensity = if (state.timeMinutes > 300f) 1.5f else 0.3f
                    RainOverlay(intensity = rainIntensity)
                }
                HUDOverlay(state)
                JoystickOverlay { dx, dy -> joyX = dx; joyY = dy }
            }
            GamePhase.INSIDE_HOUSE -> {
                InsideHouseRenderer(state)
                HUDOverlay(state)
                JoystickOverlay { dx, dy -> joyX = dx; joyY = dy }
                InteractButton { viewModel.interact() }
                
                if (state.isSleeping) {
                    SleepingOverlay { viewModel.wakeUp() }
                }
            }
            GamePhase.HACKING -> HackingScreen(state, viewModel)
            GamePhase.GAME_OVER -> GameOverScreen(state, viewModel)
            GamePhase.VICTORY -> VictoryScreen(state, viewModel)
        }
    }
}

@Composable
fun RainOverlay(intensity: Float) {
    val time = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        time.animateTo(1000f, infiniteRepeatable(tween(2000, easing = LinearEasing)))
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val rand = java.util.Random(123)
        val count = (150 * intensity).toInt()
        for(i in 0..count) {
            val startX = rand.nextFloat() * size.width
            val startY = (rand.nextFloat() * size.height + time.value * rand.nextFloat() * 1000) % size.height
            drawLine(
                color = Color.White.copy(alpha = 0.4f),
                start = Offset(startX, startY),
                end = Offset(startX - 15f * intensity, startY + 40f * intensity),
                strokeWidth = 2f * intensity
            )
        }
    }
}

@Composable
fun MainMenuScreen(state: GameState, viewModel: GameViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("HURRICANE HEIST", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(modifier = Modifier.height(8.dp))
        Text("3:13 AM. Private island. 3D Open World.", color = Color.LightGray, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(onClick = { viewModel.startGame(); SoundEffects.playBlip() }, modifier = Modifier.fillMaxWidth(0.8f)) {
            Text("SINGLE PLAYER")
        }
        Spacer(modifier = Modifier.height(32.dp))
        
        Text("Graphics Setting: ${state.graphicsSetting}", color = Color.White)
        Row {
            Button(
                onClick = { viewModel.setGraphics("Medium") }, 
                modifier = Modifier.padding(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (state.graphicsSetting == "Medium") MaterialTheme.colorScheme.primary else Color.Gray)
            ) {
                Text("Medium")
            }
            Button(
                onClick = { viewModel.setGraphics("Realistic") }, 
                modifier = Modifier.padding(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (state.graphicsSetting == "Realistic") MaterialTheme.colorScheme.primary else Color.Gray)
            ) {
                Text("Super Realistic")
            }
        }
    }
}

data class Renderable(val y: Float, val type: String, val obj: Any?)

@Composable
fun WorldRenderer(state: GameState) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cw = size.width
        val ch = size.height
        
        translate(cw / 2f - state.playerX, ch / 2f - state.playerY + 200f) {
            drawRect(Color(0xFF001933), size = Size(4000f, 4000f)) // Ocean
            drawCircle(Color(0xFFC2B280), radius = 1520f, center = Offset(2000f, 2000f)) // Beach
            drawCircle(Color(0xFF003311), radius = 1450f, center = Offset(2000f, 2000f)) // Island Grass

            val renderables = mutableListOf<Renderable>()
            
            for (h in state.houses) {
                renderables.add(Renderable(h.rect.bottom, "house", h))
            }
            renderables.add(Renderable(state.mansion.rect.bottom, "mansion", state.mansion))
            
            for (g in state.guards) {
                renderables.add(Renderable(g.y, "guard", g))
            }
            renderables.add(Renderable(state.playerY, "player", null))

            renderables.sortBy { it.y }

            for (r in renderables) {
                when (r.type) {
                    "house" -> {
                        val h = r.obj as GameObject
                        val color = if (h.id in state.serverHouseIds) Color(0xFF666666) else Color(0xFF444444)
                        val height3D = 60f
                        drawRect(Color(0xFF222222), topLeft = h.rect.topLeft, size = Size(h.rect.width, h.rect.height))
                        drawRect(color, topLeft = Offset(h.rect.left, h.rect.top - height3D), size = h.rect.size)
                    }
                    "mansion" -> {
                        val m = r.obj as GameObject
                        val height3D = 120f
                        drawRect(Color(0xFF111111), topLeft = m.rect.topLeft, size = m.rect.size)
                        drawRect(Color(0xFF333333), topLeft = Offset(m.rect.left, m.rect.top - height3D), size = m.rect.size)
                    }
                    "guard" -> {
                        val g = r.obj as Guard
                        val height3D = 30f
                        drawRect(Color.Red, topLeft = Offset(g.x - 10f, g.y - height3D), size = Size(20f, height3D))
                        drawCircle(Color(0xFFFFCCCC), radius = 8f, center = Offset(g.x, g.y - height3D - 8f))
                        drawOval(Color.Black.copy(alpha = 0.5f), topLeft = Offset(g.x - 15f, g.y - 5f), size = Size(30f, 10f))
                    }
                    "player" -> {
                        if (state.inKayak) {
                            drawOval(Color.Yellow, topLeft = Offset(state.playerX - 15f, state.playerY - 30f), size = Size(30f, 60f))
                            drawCircle(Color.Cyan, radius = 10f, center = Offset(state.playerX, state.playerY - 10f))
                        } else {
                            val height3D = 30f
                            drawOval(Color.Black.copy(alpha = 0.5f), topLeft = Offset(state.playerX - 15f, state.playerY - 5f), size = Size(30f, 10f))
                            drawRect(Color.Cyan, topLeft = Offset(state.playerX - 10f, state.playerY - height3D), size = Size(20f, height3D))
                            drawCircle(Color(0xFFFFE0BD), radius = 10f, center = Offset(state.playerX, state.playerY - height3D - 10f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InsideHouseRenderer(state: GameState) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cw = size.width
        val ch = size.height
        
        translate(cw / 2f - state.playerX, ch / 2f - state.playerY + 200f) {
            drawRect(Color(0xFF443322), size = Size(800f, 800f)) // Floor
            drawRect(Color.Black, size = Size(800f, 800f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 20f))
            drawRect(Color(0xFF221100), topLeft = Offset(0f, -100f), size = Size(800f, 100f))
            drawRect(Color(0xFF332211), topLeft = Offset(-100f, -100f), size = Size(100f, 900f))
            
            drawRect(Color(0xFF664422), topLeft = Offset(350f, 780f), size = Size(100f, 20f)) // Door
            
            drawRect(Color(0xFF888888), topLeft = Offset(500f, 500f), size = Size(250f, 250f)) // Bathroom
            drawRect(Color(0xFF666666), topLeft = Offset(500f, 450f), size = Size(250f, 50f))
            
            drawRect(Color(0xFFAAAAAA), topLeft = Offset(550f, 550f), size = Size(150f, 150f)) // Shower
            drawRect(Color(0xFF664422), topLeft = Offset(510f, 700f), size = Size(40f, 40f)) // Drawer 1
            drawRect(Color(0xFF664422), topLeft = Offset(560f, 700f), size = Size(40f, 40f)) // Drawer 2

            val renderables = mutableListOf<Renderable>()
            
            renderables.add(Renderable(350f, "bed", null))
            renderables.add(Renderable(200f, "desk", null))
            renderables.add(Renderable(state.playerY, "player", null))

            renderables.sortBy { it.y }

            for (r in renderables) {
                when (r.type) {
                    "bed" -> {
                        drawRect(Color(0xFF224466), topLeft = Offset(50f, 50f), size = Size(200f, 300f))
                        drawRect(Color(0xFF112233), topLeft = Offset(50f, 350f), size = Size(200f, 30f)) 
                    }
                    "desk" -> {
                        val hasServer = state.currentHouseId in state.serverHouseIds
                        drawRect(Color(0xFF555555), topLeft = Offset(450f, 50f), size = Size(300f, 150f))
                        drawRect(Color(0xFF333333), topLeft = Offset(450f, 200f), size = Size(300f, 40f))
                        if (hasServer) {
                            drawRect(Color.Green, topLeft = Offset(550f, 50f), size = Size(100f, 50f))
                            drawRect(Color(0xFF00AA00), topLeft = Offset(550f, 100f), size = Size(100f, 20f))
                        }
                    }
                    "player" -> {
                        val height3D = 30f
                        drawOval(Color.Black.copy(alpha = 0.5f), topLeft = Offset(state.playerX - 15f, state.playerY - 5f), size = Size(30f, 10f))
                        drawRect(Color.Cyan, topLeft = Offset(state.playerX - 10f, state.playerY - height3D), size = Size(20f, height3D))
                        drawCircle(Color(0xFFFFE0BD), radius = 10f, center = Offset(state.playerX, state.playerY - height3D - 10f))
                    }
                }
            }
        }
    }
}

@Composable
fun HUDOverlay(state: GameState) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Time: ${formatTime(state.timeMinutes.toInt())}", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (state.timeMinutes >= 360f && state.timeMinutes < 420f) {
            Text("HURRICANE ACTIVE! SEEK SHELTER!", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text("Servers: ${state.serversHacked}/3", color = Color.Cyan, fontSize = 18.sp)
        if (state.phase == GamePhase.WORLD && state.inKayak) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.stamina / 100f },
                color = if (state.stamina < 30) Color.Red else Color.Green,
                modifier = Modifier.width(150.dp).height(8.dp)
            )
            Text("Stamina", color = Color.White)
        }
        if (state.phase == GamePhase.INSIDE_HOUSE) {
            if (state.foundShampoo) Text("Shampoo Found", color = Color.Yellow)
            if (state.foundSoap) Text("Soap Found", color = Color.Yellow)
            if (state.serversHacked == 3) Text("Servers Hacked. Go to bed.", color = Color.Yellow)
        }
    }
}

@Composable
fun BoxScope.JoystickOverlay(onMove: (Float, Float) -> Unit) {
    Box(modifier = Modifier.align(Alignment.BottomStart).padding(32.dp)) {
        Joystick(onMove)
    }
}

@Composable
fun Joystick(onMove: (Float, Float) -> Unit) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 150f
    
    Box(
        modifier = Modifier
            .size(150.dp)
            .background(Color.White.copy(alpha = 0.2f), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { 
                        offset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        offset = Offset.Zero
                        onMove(0f, 0f)
                    }
                ) { change, dragAmount ->
                    change.consume()
                    val newOffset = offset + dragAmount
                    val dist = newOffset.getDistance()
                    offset = if (dist > maxRadius) {
                        newOffset / dist * maxRadius
                    } else {
                        newOffset
                    }
                    onMove(offset.x / maxRadius, offset.y / maxRadius)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .size(50.dp)
                .background(Color.White.copy(alpha = 0.5f), CircleShape)
        )
    }
}

@Composable
fun BoxScope.InteractButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp).size(80.dp),
        shape = CircleShape
    ) {
        Text("ACTION")
    }
}

@Composable
fun SleepingOverlay(onWakeUp: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sleeping... Hurricane raging outside...", color = Color.White, fontSize = 20.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onWakeUp) { Text("Wake up at 7:30 AM") }
        }
    }
}

@Composable
fun HackingScreen(state: GameState, viewModel: GameViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("SERVER BREACH IN PROGRESS", color = Color.Red, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Type exactly to bypass security:", color = Color.White)
        Text(state.hackingTarget, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = state.hackingInput,
            onValueChange = { viewModel.submitHacking(it) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.Green, unfocusedTextColor = Color.Green,
                focusedBorderColor = Color.Green, unfocusedBorderColor = Color.DarkGray
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Trace time: ${"%.1f".format(state.hackingTime)}s", color = Color.Red, fontSize = 24.sp)
    }
}

@Composable
fun GameOverScreen(state: GameState, viewModel: GameViewModel) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("YOU DIED", fontSize = 48.sp, color = Color.Red, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text(state.deathReason, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { viewModel.restart(); SoundEffects.playBlip() }) { Text("RESTART") }
    }
}

@Composable
fun VictoryScreen(state: GameState, viewModel: GameViewModel) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("MISSION ACCOMPLISHED", fontSize = 36.sp, color = Color.Green, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(16.dp))
        Text("You successfully hacked the servers, survived the hurricane, and escaped the island.", color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { viewModel.restart(); SoundEffects.playBlip() }) { Text("PLAY AGAIN") }
    }
}
