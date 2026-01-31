package com.dd3boh.outertune.playback

import android.content.Context
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.utils.DeckState
import com.dd3boh.outertune.utils.TransitionMixer
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.dd3boh.outertune.transition.playback.SilenceAudioProcessor
import com.dd3boh.outertune.transition.math.TransitionMath
import com.dd3boh.outertune.transition.model.TransitionConfig
import com.dd3boh.outertune.transition.model.TransitionPlan
import kotlin.math.abs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Manages two ExoPlayer instances (Deck A and Deck B) to enable seamless mixing.
 * Handles Volume Crossfading, Speed Ramping (Beatmatching), and EQ/Filter Effects.
 */
class DeckManager(
    private val context: Context,
    private val dataSourceFactoryProvider: () -> DataSource.Factory,
    private val onActiveDeckChanged: (ExoPlayer) -> Unit
) {
    private val TAG = "DeckManager"

    // Silence Processors for Hot-Decking
    private val silenceProcessorA = SilenceAudioProcessor()
    private val silenceProcessorB = SilenceAudioProcessor()

    val playerA: ExoPlayer = createPlayer("A")
    // Deck B only created when needed, saving resources
    private val playerB: ExoPlayer by lazy {
        Log.d(TAG, "Creating Deck B for DJ mode")
        createPlayer("B")
    }

    // Audio Effects (Graphic Equalizer)
    private var eqA: Equalizer? = null
    private var eqB: Equalizer? = null

    var activeDeck: ExoPlayer = playerA
        private set
    var standbyDeck: ExoPlayer = getStandby()
        private set

    private fun getStandby(): ExoPlayer {
        // If active is A, standby is B (init if needed)
        // This circular dependency lazy logic is tricky.
        // Let's just rely on logic:
        return if (activeDeck == playerA) playerB else playerA
    }
    
    // ...

    private val _activeDeckSpeed = MutableStateFlow(1f)
    val activeDeckSpeed = _activeDeckSpeed.asStateFlow()

    private val _standbyDeckSpeed = MutableStateFlow(1f)
    val standbyDeckSpeed = _standbyDeckSpeed.asStateFlow()
    // ------------------------------------

    private val mixerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fadeJob: Job? = null
    private var speedJob: Job? = null

    private val _isCrossfading = MutableStateFlow(false)
    val isCrossfading = _isCrossfading.asStateFlow()

    private var activeMultiplier = 1f
    private var standbyMultiplier = 1f

    fun setStandbyVolumeMultiplier(multiplier: Float) {
        standbyMultiplier = multiplier
        Log.d(TAG, "Standby Multiplier set to $multiplier")
    }

    private fun completeTransition() {
        Log.e(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.e(TAG, "║          completeTransition() CALLED                       ║")
        Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")

        Log.e(TAG, "BEFORE SWAP:")
        Log.e(TAG, "  Active: ${if (activeDeck == playerA) "A" else "B"} - playing=${activeDeck.isPlaying}, pos=${activeDeck.currentPosition}")
        Log.e(TAG, "  Standby: ${if (standbyDeck == playerA) "A" else "B"} - playing=${standbyDeck.isPlaying}, pos=${standbyDeck.currentPosition}")

        // Swap decks
        val temp = activeDeck
        activeDeck = standbyDeck
        standbyDeck = temp

        Log.e(TAG, "AFTER SWAP:")
        Log.e(TAG, "  Active: ${if (activeDeck == playerA) "A" else "B"} - playing=${activeDeck.isPlaying}, pos=${activeDeck.currentPosition}")
        Log.e(TAG, "  Standby: ${if (standbyDeck == playerA) "A" else "B"} - playing=${standbyDeck.isPlaying}, pos=${standbyDeck.currentPosition}")

        // Swap EQ references
        val tempEq = eqA
        eqA = eqB
        eqB = tempEq

        // Swap Speed States
        val prevStandbySpeed = _standbyDeckSpeed.value
        _activeDeckSpeed.value = prevStandbySpeed
        _standbyDeckSpeed.value = 1f

        Log.e(TAG, "  Speed swapped: active=${_activeDeckSpeed.value}, standby=${_standbyDeckSpeed.value}")

        // Swap Multipliers
        activeMultiplier = standbyMultiplier
        standbyMultiplier = 1f

        Log.e(TAG, "  Multipliers swapped: active=$activeMultiplier, standby=$standbyMultiplier")

        // Notify listener
        Log.e(TAG, "  → Calling onActiveDeckChanged callback...")
        onActiveDeckChanged(activeDeck)
        Log.e(TAG, "  → Callback returned")

        // Slowly reset pitch to 1.0
        scheduleSpeedReset()

        // Clean up old deck
        Log.e(TAG, "  → Stopping old deck (now standby)...")
        standbyDeck.stop()
        standbyDeck.clearMediaItems()
        standbyDeck.volume = 1f
        standbyDeck.setPlaybackSpeed(1f)
        Log.e(TAG, "  → Old deck cleaned up")

        // Ensure active maintains its normalized volume
        activeDeck.volume = activeMultiplier
        Log.e(TAG, "  → Set new active deck volume to $activeMultiplier")

        _isCrossfading.value = false
        Log.e(TAG, "  → isCrossfading = false")
        Log.e(TAG, "╚════════════════════════════════════════════════════════════╝")
    }


    fun startPllTransition(
        plan: TransitionPlan,
        durationMs: Long,
        config: TransitionConfig
    ) {
        Log.e(TAG, "████████████████████████████████████████████████████████████")
        Log.e(TAG, "startPllTransition() CALLED")
        Log.e(TAG, "  Transition Duration: ${durationMs}ms")
        Log.e(TAG, "  Exit Beat A: ${plan.anchorBeatA}")
        Log.e(TAG, "  Entry Beat B: ${plan.anchorBeatB}")
        Log.e(TAG, "  Initial Speed B: ${plan.initialSpeedB}")
        Log.e(TAG, "  Grid A Size: ${plan.gridA.size}")
        Log.e(TAG, "  Grid B Size: ${plan.gridB.size}")
        Log.e(TAG, "  Grid A [0]: ${if (plan.gridA.isNotEmpty()) plan.gridA[0] else "EMPTY"}")
        Log.e(TAG, "  Grid B [0]: ${if (plan.gridB.isNotEmpty()) plan.gridB[0] else "EMPTY"}")

        if (fadeJob?.isActive == true) {
            Log.e(TAG, "  ⚠️ FADE JOB ALREADY ACTIVE - ABORTING!")
            Log.e(TAG, "████████████████████████████████████████████████████████████")
            return
        }

        // STRICT VALIDATION
        if (plan.gridA.isEmpty() || plan.gridB.isEmpty()) {
            Log.e(TAG, "  ⚠️ STRICT: Grids are empty! Aborting Transition.")
            Log.e(TAG, "████████████████████████████████████████████████████████████")
            return
        }
        // If we are exiting a song (not at start), anchor beat A must be > 0.
        // We allow close to 0 if the user set a transition at 0.1s, but 0.0 usually means "empty grid" returned 0.
        if (plan.exitPointMs > 5000 && plan.anchorBeatA < 1.0) {
             Log.e(TAG, "  ⚠️ STRICT: Exit Point ${plan.exitPointMs}ms but Exit Beat ${plan.anchorBeatA}. Invalid Plan.")
             Log.e(TAG, "████████████████████████████████████████████████████████████")
             return
        }

        // 1. Setup
        val pA = activeDeck
        val pB = standbyDeck
        // Use current volume to prevent jumps
        val outMult = pA.volume
        val inMult = standbyMultiplier

        Log.e(TAG, "  Active Deck: ${if (pA == playerA) "A" else "B"}")
        Log.e(TAG, "  Standby Deck: ${if (pB == playerA) "A" else "B"}")
        Log.e(TAG, "  Active Deck State: playing=${pA.isPlaying}, state=${pA.playbackState}, pos=${pA.currentPosition}")
        Log.e(TAG, "  Standby Deck State: playing=${pB.isPlaying}, state=${pB.playbackState}, pos=${pB.currentPosition}")

        // Unmute Standby (Silence Processor handles silence until we want sound)
        if (pB == playerA) {
            silenceProcessorA.isEnabled = false
            Log.e(TAG, "  → Disabled Silence Processor A")
        } else {
            silenceProcessorB.isEnabled = false
            Log.e(TAG, "  → Disabled Silence Processor B")
        }

        // Ensure EQ
        val eqOut = ensureEq(pA)
        val eqIn = ensureEq(pB)
        Log.e(TAG, "  → EQ initialized: Out=${eqOut != null}, In=${eqIn != null}")

        // Calculate the actual start position (3 seconds before entry point)
        val prerollSeconds = 3.0
        val startB = (TransitionMath.getTimestampForBeat(plan.gridB, plan.anchorBeatB) - (prerollSeconds * plan.initialSpeedB)).coerceAtLeast(0.0)



        // CRITICAL FIX: Account for monitoring delays by predicting future positions
        // The problem: During the ~100ms of monitoring, both decks advance, creating drift
        // Solution: Calculate where deck A will be AFTER the monitoring delay, then seek B accordingly
        
        val monitoringDelayMs = 50L // Estimated time for seek completion check + loop startup
        val predictedPosA = pA.currentPosition + monitoringDelayMs
        val predictedTimeA = predictedPosA / 1000.0
        val predictedBeatA = TransitionMath.getBeatForTimestamp(plan.gridA, predictedTimeA)
        val predictedElapsedBeats = predictedBeatA - plan.anchorBeatA
        val predictedTargetBeatB = plan.anchorBeatB + predictedElapsedBeats
        val predictedTargetTimeB = TransitionMath.getTimestampForBeat(plan.gridB, predictedTargetBeatB)
        val targetPos = (predictedTargetTimeB * 1000).toLong()
        
        Log.e(TAG, "  ═══════════════════════════════════════════════════════════")
        Log.e(TAG, "  🔍 DIAGNOSTIC: Pre-Seek State")
        Log.e(TAG, "  ═══════════════════════════════════════════════════════════")
        Log.e(TAG, "  → Current Position A: ${pA.currentPosition}ms")
        Log.e(TAG, "  → Predicted Position A (after ${monitoringDelayMs}ms): ${predictedPosA}ms")
        Log.e(TAG, "  → Predicted Beat A: $predictedBeatA")
        Log.e(TAG, "  → Predicted Target Beat B: $predictedTargetBeatB")
        Log.e(TAG, "  → Current Position B: ${pB.currentPosition}ms")
        Log.e(TAG, "  → Target Position B: ${targetPos}ms")
        Log.e(TAG, "  → Position Delta: ${targetPos - pB.currentPosition}ms")
        Log.e(TAG, "  → Playback State B: ${when(pB.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN(${pB.playbackState})"
        }}")
        Log.e(TAG, "  → Is Playing B: ${pB.isPlaying}")
        Log.e(TAG, "  → PlayWhenReady B: ${pB.playWhenReady}")
        
        val seekStartTime = System.currentTimeMillis()
        pB.seekTo(targetPos)
        Log.e(TAG, "  → Seek command issued to predicted position")
        
        pB.setPlaybackSpeed(plan.initialSpeedB.toFloat())
        _standbyDeckSpeed.value = plan.initialSpeedB.toFloat()

        Log.e(TAG, "  → Set Standby speed to ${plan.initialSpeedB}")

        // NOW start playback - this is crucial!
        pB.playWhenReady = true
        pB.play()

        Log.e(TAG, "  → Called pB.play() - Standby should now be PLAYING")

        _isCrossfading.value = true
        Log.e(TAG, "  → Set isCrossfading = true")

        fadeJob = mixerScope.launch {
            // Minimal monitoring - just verify seek completed, then start PLL immediately
            delay(30) // Short delay for seek to register
            
            val seekDuration = System.currentTimeMillis() - seekStartTime
            val finalPos = pB.currentPosition
            val seekAccuracy = abs(finalPos - targetPos)
            
            Log.e(TAG, "  ═══════════════════════════════════════════════════════════")
            Log.e(TAG, "  🔍 DIAGNOSTIC: Post-Seek State")
            Log.e(TAG, "  ═══════════════════════════════════════════════════════════")
            Log.e(TAG, "  → Seek Duration: ${seekDuration}ms")
            Log.e(TAG, "  → Final Position B: ${finalPos}ms")
            Log.e(TAG, "  → Seek Accuracy: ${seekAccuracy}ms (${if (seekAccuracy < 100) "GOOD" else "POOR"})")
            Log.e(TAG, "  → Final State B: ${when(pB.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }}")
            
            if (seekAccuracy > 150) {
                Log.e(TAG, "  ⚠️ WARNING: Seek accuracy is poor! This suggests COLD START buffering delay")
            }
            
            if (pB.playbackState != Player.STATE_READY) {
                Log.e(TAG, "  ⚠️ WARNING: Deck not in READY state after seek! State=${pB.playbackState}")
            }
            
            // Check if playback actually started
            delay(20)
            val posAfterDelay = pB.currentPosition
            val advancement = posAfterDelay - finalPos
            Log.e(TAG, "  → Position 20ms later: ${posAfterDelay}ms (advanced ${advancement}ms)")
            
            if (advancement < 5) {
                Log.e(TAG, "  ⚠️ WARNING: Position hasn't advanced much! Playback may be stalled")
            }
            
            Log.e(TAG, "  → Standby State: playing=${pB.isPlaying}, playWhenReady=${pB.playWhenReady}")
            Log.e(TAG, "  ═══════════════════════════════════════════════════════════")
            
            // Calculate initial phase error
            val posA = pA.currentPosition / 1000.0
            val posB = pB.currentPosition / 1000.0
            val currentBeatA = TransitionMath.getBeatForTimestamp(plan.gridA, posA)
            val currentBeatB = TransitionMath.getBeatForTimestamp(plan.gridB, posB)
            val elapsedBeatsA = currentBeatA - plan.anchorBeatA
            val targetBeatB = plan.anchorBeatB + elapsedBeatsA
            val initialPhaseError = targetBeatB - currentBeatB
            
            Log.e(TAG, "  ═══════════════════════════════════════════════════════════")
            Log.e(TAG, "  🔍 DIAGNOSTIC: Pre-Loop State (T+${seekDuration + 50}ms)")
            Log.e(TAG, "  ═══════════════════════════════════════════════════════════")
            Log.e(TAG, "  → Active Deck Position: ${pA.currentPosition}ms")
            Log.e(TAG, "  → Standby Deck Position: ${pB.currentPosition}ms")
            Log.e(TAG, "  → Standby playing=${pB.isPlaying}, state=${pB.playbackState}")
            Log.e(TAG, "  → Initial Beat A: $currentBeatA")
            Log.e(TAG, "  → Initial Beat B: $currentBeatB")
            Log.e(TAG, "  → Target Beat B: $targetBeatB")
            Log.e(TAG, "  → INITIAL PHASE ERROR: $initialPhaseError beats")
            
            if (abs(initialPhaseError) > 0.2) {
                Log.e(TAG, "  ⚠️⚠️⚠️ CRITICAL: Large initial phase error! This is the COLD START problem!")
            } else if (abs(initialPhaseError) < 0.05) {
                Log.e(TAG, "  ✓✓✓ EXCELLENT: Small initial phase error! This is a WARM START")
            } else {
                Log.e(TAG, "  ⚠️ MODERATE: Initial phase error is acceptable but not perfect")
            }
            
            Log.e(TAG, "  ═══════════════════════════════════════════════════════════")
            Log.e(TAG, "  → PLL Loop Starting...")
            Log.e(TAG, "████████████████████████████████████████████████████████████")
            runPllLoop(pA, pB, plan, durationMs, config, outMult, inMult, eqOut, eqIn)
        }
    }

    private suspend fun runPllLoop(
        pA: ExoPlayer,
        pB: ExoPlayer,
        plan: TransitionPlan,
        durationMs: Long,
        config: TransitionConfig,
        outMult: Float,
        inMult: Float,
        eqOut: Equalizer?,
        eqIn: Equalizer?
    ) {
        Log.i(TAG, "▶▶▶ runPllLoop() STARTED ◀◀◀")

        val transitionStartBeatA = plan.anchorBeatA
        var currentAppliedSpeed = plan.initialSpeedB.toFloat()
        var previousPhaseError = 0.0 // For derivative term

        val bandsOut = eqOut?.numberOfBands ?: 0.toShort()
        val bandsIn = eqIn?.numberOfBands ?: 0.toShort()
        val minLevel = (-1500).toShort()

        Log.d(TAG, "PLL Config: AnchorBeatA=$transitionStartBeatA, InitSpeed=$currentAppliedSpeed")

        var loopCount = 0
        val startTime = System.currentTimeMillis()
        var hasEnteredCrossfade = false // Track when we first enter crossfade

        while (currentCoroutineContext().isActive) {
            loopCount++
            val posA = pA.currentPosition / 1000.0
            val posB = pB.currentPosition / 1000.0

            // Log every 500ms
            if (loopCount % 25 == 1) {
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "PLL Loop #$loopCount (${elapsed}ms): " +
                        "PosA=${posA}s, PosB=${posB}s, " +
                        "PlayingA=${pA.isPlaying}, PlayingB=${pB.isPlaying}, " +
                        "StateA=${pA.playbackState}, StateB=${pB.playbackState}, " +
                        "VolA=${pA.volume}, VolB=${pB.volume}")
            }

            // --- 1. Beat Calculations ---
            val currentBeatA = TransitionMath.getBeatForTimestamp(plan.gridA, posA)
            val elapsedBeatsA = currentBeatA - transitionStartBeatA

            // Phase Locking
            val targetBeatB = plan.anchorBeatB + elapsedBeatsA
            val currentBeatB = TransitionMath.getBeatForTimestamp(plan.gridB, posB)

            var phaseError = targetBeatB - currentBeatB

            if (loopCount % 50 == 1) { // Log beat details every ~1s (loop runs at 20ms)
                Log.d(TAG, "  [Beats] CurA=$currentBeatA, ElapA=$elapsedBeatsA, TgtB=$targetBeatB, CurB=$currentBeatB")
                Log.d(TAG, "  [Phase] Error=$phaseError")
            }
            
            // Initialize previousPhaseError on first loop to prevent huge derivative spike
            if (loopCount == 1) {
                previousPhaseError = phaseError
            }

            // --- 2. Mixing Progress Calculation ---
            val progress = if (durationMs > 0)
                ((pA.currentPosition - (TransitionMath.getTimestampForBeat(plan.gridA, plan.anchorBeatA)*1000)).toFloat() / durationMs).coerceIn(-1f, 2f)
            else 1f

            // --- 3. Phase Correction (Runs in BOTH Preroll and Crossfade) ---
            if (progress < 0f) {
                // PREROLL PHASE - PD CONTROLLER (Proportional + Derivative)
                // CRITICAL: NO SEEKS during preroll! Each seek introduces latency and drift.
                // Use PD controller to prevent oscillation and overshoot.
                
                if (abs(phaseError) > 0.005) {
                    // PD Controller: combines proportional and derivative terms
                    val kp = 0.35f // Proportional gain (increased for faster convergence)
                    val kd = 0.08f // Derivative gain (reduced for stability)
                    
                    // Derivative term: how fast is the error changing?
                    val errorDerivative = phaseError - previousPhaseError
                    
                    // PD control: nudge = kp * error + kd * derivative
                    val proportionalTerm = phaseError * kp
                    val derivativeTerm = errorDerivative * kd
                    val nudge = (proportionalTerm + derivativeTerm).coerceIn(-0.2, 0.2)
                    
                    val newSpeed = (plan.initialSpeedB + nudge).toFloat().coerceIn(0.8f, 1.2f)

                    if (abs(newSpeed - currentAppliedSpeed) > 0.001f) {
                        pB.setPlaybackSpeed(newSpeed)
                        currentAppliedSpeed = newSpeed
                        _standbyDeckSpeed.value = newSpeed
                        
                        if (loopCount % 25 == 1 || loopCount <= 15) {
                            Log.d(TAG, "  [PREROLL PD] Loop#$loopCount Error=$phaseError, Deriv=$errorDerivative -> P=$proportionalTerm, D=$derivativeTerm -> Speed=$newSpeed")
                        }
                    }
                } else {
                    // Phase error is tiny - we're well aligned!
                    if (loopCount % 50 == 1) {
                        Log.d(TAG, "  [PREROLL] Loop#$loopCount ALIGNED! phaseError=$phaseError")
                    }
                }
                
                // Update previous error for next iteration
                previousPhaseError = phaseError
                
                // Set volumes (Track B muted during preroll)
                pA.volume = 1f * outMult
                pB.volume = 0f

                if (loopCount % 25 == 1 || loopCount <= 10) {
                    Log.d(TAG, "  [PREROLL] Loop#$loopCount progress=$progress, phaseError=$phaseError")
                }
            } else if (progress <= 1f) {
                // CROSSFADE PHASE - GENTLE CORRECTION (Both tracks audible)
                
                // Log the moment we enter crossfade for the first time
                if (!hasEnteredCrossfade) {
                    hasEnteredCrossfade = true
                    Log.e(TAG, "  ╔═══════════════════════════════════════════════════════════╗")
                    Log.e(TAG, "  ║  🎵 CROSSFADE STARTED - TRACK 2 NOW AUDIBLE               ║")
                    Log.e(TAG, "  ╚═══════════════════════════════════════════════════════════╝")
                    Log.e(TAG, "  → Loop Count: $loopCount")
                    Log.e(TAG, "  → Time Since Start: ${System.currentTimeMillis() - startTime}ms")
                    Log.e(TAG, "  → Progress: $progress")
                    Log.e(TAG, "  → PHASE ERROR AT UNMUTE: $phaseError beats")
                    Log.e(TAG, "  → Current Beat A: $currentBeatA")
                    Log.e(TAG, "  → Current Beat B: $currentBeatB")
                    Log.e(TAG, "  → Target Beat B: $targetBeatB")
                    
                    if (abs(phaseError) > 0.1) {
                        Log.e(TAG, "  ⚠️⚠️⚠️ POOR ALIGNMENT AT UNMUTE! User will hear misalignment!")
                    } else if (abs(phaseError) < 0.05) {
                        Log.e(TAG, "  ✓✓✓ EXCELLENT ALIGNMENT AT UNMUTE! Perfect beatmatch!")
                    } else {
                        Log.e(TAG, "  ⚠️ MODERATE ALIGNMENT - Slight drift may be audible")
                    }
                    Log.e(TAG, "  ═══════════════════════════════════════════════════════════")
                }
                
                if (abs(phaseError) > 0.005) {
                    // Use gentler PLL during crossfade to avoid audible artifacts
                    val kp = 0.1f
                    val nudge = (phaseError * kp).coerceIn(-0.1, 0.1)
                    val newSpeed = (plan.initialSpeedB + nudge).toFloat().coerceIn(0.5f, 2.0f)

                    if (abs(newSpeed - currentAppliedSpeed) > 0.002f) {
                        pB.setPlaybackSpeed(newSpeed)
                        currentAppliedSpeed = newSpeed
                        _standbyDeckSpeed.value = newSpeed

                        if (loopCount % 25 == 1) {
                            Log.d(TAG, "  [CROSSFADE PLL] PhaseError=$phaseError -> Nudge=$nudge -> Speed=$newSpeed")
                        }
                    }
                }
                
                // Set volumes based on mixer state
                // CROSSFADE ACTIVE
                val stateOut = TransitionMixer.getMixState("A", progress, config.overlapMode, config.eqMode, config.effectMode)
                val stateIn = TransitionMixer.getMixState("B", progress, config.overlapMode, config.eqMode, config.effectMode)

                pA.volume = stateOut.volume * outMult
                pB.volume = stateIn.volume * inMult

                if (loopCount % 25 == 1) {
                    Log.d(TAG, "  CROSSFADE: progress=$progress, volA=${pA.volume}, volB=${pB.volume}")
                }

                applyDeckStateToEQ(eqOut, stateOut, minLevel, bandsOut, config.effectMode)
                applyDeckStateToEQ(eqIn, stateIn, minLevel, bandsIn, config.effectMode)
            } else {
                // FINISHED
                Log.i(TAG, "▶▶▶ PLL Loop FINISHED (progress=$progress) ◀◀◀")
                break
            }

            delay(20)
        }

        Log.i(TAG, "▶▶▶ runPllLoop() EXITING - Calling completeTransition() ◀◀◀")
        completeTransition()
    }


    private fun scheduleSpeedReset(durationMs: Long = 10000) {
        speedJob?.cancel()

        val player = activeDeck
        val startSpeed = player.playbackParameters.speed

        // Optimization: If speed is already very close to 1.0 (within 1%), just snap it instantly.
        // This avoids running the Sonic processor for 10s unnecessarily, which causes "choppy" artifacts.
        if (abs(startSpeed - 1f) < 0.01f) {
            if (startSpeed != 1f) player.setPlaybackSpeed(1f)
            _activeDeckSpeed.value = 1f
            return
        }

        Log.i(TAG, "Ramping BPM from $startSpeed to 1.0 over ${durationMs}ms")

        speedJob = mixerScope.launch {
            val startTime = System.currentTimeMillis()
            val endTime = startTime + durationMs

            while (isActive && System.currentTimeMillis() < endTime) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = elapsed.toFloat() / durationMs

                // Linear interpolation
                val newSpeed = startSpeed + (1f - startSpeed) * progress
                player.setPlaybackSpeed(newSpeed)
                _activeDeckSpeed.value = newSpeed

                // Reduce update frequency to 500ms to allow Sonic to stabilize
                delay(500)
            }
            player.setPlaybackSpeed(1f)
            _activeDeckSpeed.value = 1f
        }
    }

    fun cancelCrossfade() {
        fadeJob?.cancel()
        activeDeck.volume = 1f
        standbyDeck.volume = 0f
        standbyDeck.pause()
        resetEQ(eqA)
        resetEQ(eqB)
    }

    // --- Equalizer Helpers ---

    private fun ensureEq(player: ExoPlayer): Equalizer? {
        // If player session ID is invalid, we can't attach
        if (player.audioSessionId == 0) return null

        val isPlayerA = (player == playerA)
        var eq = if (isPlayerA) eqA else eqB

        // Create if missing or if session ID changed
        if (eq == null) {
            try {
                eq = Equalizer(0, player.audioSessionId)
                eq.enabled = true
                if (isPlayerA) eqA = eq else eqB = eq
                Log.d(TAG, "Initialized EQ for Deck ${if (isPlayerA) "A" else "B"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init EQ", e)
            }
        }
        return eq
    }

    private fun resetEQ(eq: Equalizer?) {
        if (eq == null) return
        try {
            for (i in 0 until eq.numberOfBands) {
                eq.setBandLevel(i.toShort(), 0)
            }
        } catch (e: Exception) { Log.e(TAG, "Error resetting EQ", e) }
    }

    /**
     * Maps the abstract DeckState (Bass 0..1, Filter 0..1) to physical Android Equalizer bands.
     * This approximates a DJ mixer's Low/High pass filters.
     */
    private fun applyDeckStateToEQ(
        eq: Equalizer?,
        state: DeckState,
        minEQ: Short,
        bands: Short,
        effectMode: String
    ) {
        if (eq == null || bands < 1) return
        try {
            // 1. Bass Cut logic
            // We usually cut the lowest band (Index 0)
            val bassLevel = state.bass
            val bassCut = (minEQ * (1f - bassLevel)).toInt().toShort()

            eq.setBandLevel(0.toShort(), bassCut)
            // If we have enough bands, slightly cut the second one too for smoother roll-off
            if (bands > 1) {
                eq.setBandLevel(1.toShort(), (bassCut * 0.8).toInt().toShort())
            }

            // 2. Filter Logic (LPF / HPF)
            // Standard Android EQ is usually 5 bands.
            // Band 0: ~60Hz, Band 4: ~14kHz

            val isLPF = effectMode.contains("Low pass") // "Low pass in" or "Low Pass out"
            val isHPF = effectMode.contains("High Pass")
            val filterLevel = state.filterHigh

            if (isLPF) {
                // Low Pass: Attenuate High Frequencies
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()
                val lastBand = (bands - 1).toShort()

                eq.setBandLevel(lastBand, cut)
                if (bands > 2) {
                    val prevBand = (bands - 2).toShort()
                    eq.setBandLevel(prevBand, (cut * 0.7).toInt().toShort())
                }
            } else if (isHPF) {
                // High Pass: Attenuate Low Frequencies
                // Note: If Bass cut is also active, use whichever is stronger (minimum)
                val cut = (minEQ * (1f - filterLevel)).toInt().toShort()

                val currentBand0 = eq.getBandLevel(0.toShort())
                if (cut < currentBand0) eq.setBandLevel(0.toShort(), cut)

                if (bands > 1) {
                    val currentBand1 = eq.getBandLevel(1.toShort())
                    if (cut < currentBand1) eq.setBandLevel(1.toShort(), cut)
                }
                if (bands > 2) {
                    // Cut mids partially
                    eq.setBandLevel(2.toShort(), (cut * 0.5).toInt().toShort())
                }
            }
        } catch (e: Exception) {
            // EQ failures are non-critical, just log
            // Log.w(TAG, "EQ Set failed", e)
        }
    }

    fun release() {
        mixerScope.cancel()
        eqA?.release()
        eqB?.release()
        playerA.release()
        playerB.release()
    }

    // --- Player Creation & Hot Decking ---

    private fun createPlayer(label: String): ExoPlayer {
        val silenceProcessor = if (label == "A") silenceProcessorA else silenceProcessorB
        
        // We use the same factory logic as TransitionPlaybackEngine to ensure consistency
        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                pcmEncodingRestrictionLifted: Boolean,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setPcmEncodingRestrictionLifted(pcmEncodingRestrictionLifted)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setEnableFloatOutput(enableFloatOutput) // Enable 32-bit Float High-Res Audio
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                            androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor(),
                            androidx.media3.common.audio.SonicAudioProcessor(),
                            silenceProcessor // INJECTED SILENCE PROCESSOR
                        )
                    )
                    .build()
            }
        }.apply {
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactoryProvider()))
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), false) // No Auto Focus
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (_isCrossfading.value && player == activeDeck) {
                    if (standbyDeck.playWhenReady != playWhenReady) {
                        Log.d(TAG, "Syncing standby deck to active deck state: playWhenReady=$playWhenReady")
                        standbyDeck.playWhenReady = playWhenReady
                    }
                }
            }
        })

        return player
    }

    fun prepareNext(mediaItem: MediaItem, startPositionMs: Long, bpmConfig: Float?) {
        Log.d(TAG, "════════════════════════════════════════════════════════════")
        Log.d(TAG, "prepareNext() CALLED")
        Log.d(TAG, "  Song: ${mediaItem.mediaMetadata.title}")
        Log.d(TAG, "  Start Position: ${startPositionMs}ms")
        Log.d(TAG, "  BPM Config: $bpmConfig")
        Log.d(TAG, "  Current Active Deck: ${if (activeDeck == playerA) "A" else "B"}")
        Log.d(TAG, "  Current Standby Deck: ${if (standbyDeck == playerA) "A" else "B"}")
        Log.d(TAG, "════════════════════════════════════════════════════════════")

        // 1. Reset
        standbyDeck.stop()
        standbyDeck.clearMediaItems()
        standbyDeck.setMediaItem(mediaItem)

        // 2. Enable Silence (Hot Deck)
        if (standbyDeck == playerA) {
            silenceProcessorA.isEnabled = true
            Log.d(TAG, "  → Enabled Silence Processor A")
        } else {
            silenceProcessorB.isEnabled = true
            Log.d(TAG, "  → Enabled Silence Processor B")
        }

        // 3. Set Volume to 0 (Silence processor also helps)
        standbyDeck.volume = 0f
        Log.d(TAG, "  → Set standby volume to 0")

        // 4. Seek & Speed
        standbyDeck.seekTo(startPositionMs)
        val speed = bpmConfig ?: 1f
        standbyDeck.setPlaybackSpeed(speed)
        _standbyDeckSpeed.value = speed
        Log.d(TAG, "  → Seek to ${startPositionMs}ms, speed: $speed")

        standbyDeck.prepare()
        Log.d(TAG, "  → Called prepare()")

        // 5. "Bite and HOLD" - Keep it prepared but paused
        mixerScope.launch {
            Log.d(TAG, "  → Waiting for standby deck to be READY...")
            var waitCount = 0
            while (standbyDeck.playbackState != Player.STATE_READY) {
                delay(10)
                waitCount++
                if (waitCount % 100 == 0) {
                    Log.d(TAG, "    Still waiting... State: ${standbyDeck.playbackState}")
                }
            }

            Log.d(TAG, "  → Standby deck is READY!")
            standbyDeck.seekTo(startPositionMs)
            standbyDeck.playWhenReady = false
            Log.d(TAG, "  → Set playWhenReady=false, deck is PAUSED and ready")
            Log.d(TAG, "════════════════════════════════════════════════════════════")
        }

        ensureEq(standbyDeck)
    }



    fun addListener(listener: Player.Listener) {
        playerA.addListener(listener)
        playerB.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        playerA.removeListener(listener)
        playerB.removeListener(listener)
    }
}