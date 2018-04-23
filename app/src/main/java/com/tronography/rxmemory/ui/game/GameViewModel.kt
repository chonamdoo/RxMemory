package com.tronography.rxmemory.ui.game

import DEBUG
import ERROR
import android.arch.lifecycle.ViewModel
import com.tronography.rxmemory.data.model.Card
import com.tronography.rxmemory.data.repository.Repository
import com.tronography.rxmemory.data.state.GameState
import com.tronography.rxmemory.data.state.GameState.*
import com.tronography.rxmemory.utilities.GlideUtils
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.observers.DisposableObserver
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameViewModel @Inject constructor(
        private val repository: Repository, val glideUtils: GlideUtils) : ViewModel() {

    private var deck: BehaviorSubject<List<Card>> = BehaviorSubject.create()
    private var gameState: BehaviorSubject<GameState> = BehaviorSubject.create()
    private var flippedCardCount: BehaviorSubject<Int> = BehaviorSubject.create()
    private var matchedCard: BehaviorSubject<Card> = BehaviorSubject.create()

    private val matchedCards = HashMap<String, Card>()
    private val flippedCards = HashMap<String, Card>()

    private var attemptCount: Int

    private var selectedCard: Card? = null


    private val disposables = CompositeDisposable()

    init {
        repository.deleteCardTable()
        attemptCount = 0
        gameState.onNext(LOADING)
        refreshCards()
    }

    fun getDeck(): Observable<List<Card>> {
        return deck
    }

    fun getMatchedCard(): Observable<Card> {
        return matchedCard
    }

    fun getFlippedCardCount(): Observable<Int> {
        return flippedCardCount
    }

    fun getGameState(): Observable<GameState> {
        return gameState
    }

    fun restartGame() {
        broadcastGameState(NOT_IN_PROGRESS)
        repository.deleteCardTable()
        flippedCards.clear()
        matchedCards.clear()
        attemptCount = 0
        refreshCards()
    }

    fun selectCard(card: Card) {
        if (!isSelected(card)) {
            when (flippedCards.isEmpty()) {
                true -> {
                    setSelectedCard(card)
                    updateFlippedCards(card)
                }
                false -> disposables.add(
                        Completable.fromAction { validateCardMatch(card) }
                                .subscribeOn(Schedulers.io())
                                .doOnComplete { resetUnmatchedFlippedCards(flippedCards) }
                                .subscribe())
            }
        }
        broadcastFlipCount()
    }

    private fun validateCardMatch(card: Card) {
        if (isValidMatch(card)) {
            broadcastGameState(MATCH_FOUND)
            matchedCard.onNext(card)
            markAsMatched(card)
        } else {
            updateFlippedCards(card)
            clearSelectedCard()
        }
    }

    private fun markAsMatched(card: Card) {
        updateFlippedCards(card)
        flippedCards.forEach {
            updateFlippedCards(it.value.matchCard(true))
            updateMatchedCards(it.value)
        }
    }

    private fun updateFlippedCards(card: Card) {
        DEBUG("UPDATING flippedCards")
        val flippedCard = card.flipCard(true)
        flippedCards.put(flippedCard.cardId, flippedCard)

        DEBUG("flippedCards = ${flippedCards.values}")
        DEBUG("flippedCards.size = ${flippedCards.size}")

        repository.updateCardFlip(flippedCard, flippedCard.isFlipped)
    }

    private fun setSelectedCard(card: Card) {
        DEBUG("SETTING SELECTED CARD")
        selectedCard = card
        DEBUG("SELECTED CARD = $selectedCard")
    }

    private fun refreshCards() {
        disposables.add(
                repository.getSprites()
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribeWith(object : DisposableObserver<List<Card>>() {
                            override fun onNext(cards: List<Card>) {
                                DEBUG("Received ${cards.size} cards from Repository")

                                if (isGameLoading()) {
                                    preloadCardImages(cards)
                                }

                                if (isGameOver(cards)) {
                                    broadcastGameState(GAME_OVER)
                                    broadcastDeckChanges(cards)
                                } else {
                                    broadcastGameState(IN_PROGRESS)
                                    broadcastDeckChanges(cards)
                                    broadcastFlipCount()
                                }
                            }

                            override fun onError(e: Throwable) {
                                ERROR(e.message.toString())
                                broadcastGameState(NOT_IN_PROGRESS)
                            }

                            override fun onComplete() {
                                ERROR("ON COMPLETE CALLED")
                            }
                        })
        )
    }

    private fun broadcastDeckChanges(cards: List<Card>) {
        deck.onNext(cards)
    }

    private fun preloadCardImages(cards: List<Card>) {
        cards.forEach { card -> glideUtils.preloadImages(card.photoUrl) }
    }

    private fun isGameLoading() = gameState.value == LOADING

    private fun resetUnmatchedFlippedCards(flippedCards: HashMap<String, Card>) {
        DEBUG("Resetting unmatched cards in flippedCards : ${flippedCards.keys}")
        delayedAction { flipUnmatchedCards(flippedCards) }
    }

    private fun isGameOver(cards: List<Card>): Boolean {
        DEBUG("matchedCards.size = ${matchedCards.size} cards.size = ${cards.size}")
        return matchedCards.size == cards.size
    }

    private fun updateMatchedCards(card: Card) {
        val matchedCard = card.matchCard(true)
        matchedCards.put(matchedCard.cardId, matchedCard)

        DEBUG("$matchedCard added to matchedCards")
        DEBUG("matchedCards.size = ${matchedCards.size}")

        repository.updateMatch(matchedCard, true)
    }

    private fun flipUnmatchedCards(cards: HashMap<String, Card>) {
        delayedAction {
            cards.filter { !it.value.isMatched }
                    .forEach { repository.updateCardFlip(it.value, false) }
            clearSelectedCard()
            flippedCards.clear()
            broadcastFlipCount()
        }
    }

    private fun clearSelectedCard() {
        DEBUG("CLEARING SELECTED CARD")
        selectedCard = null
        DEBUG("SELECTED CARD = $selectedCard")
    }

    private fun isSelected(card: Card): Boolean {
        return card.cardId == selectedCard?.cardId && !card.isMatched
    }

    private fun isValidMatch(card: Card): Boolean {
        DEBUG("isValidMatch = ${selectedCard?.cardId != card.cardId && selectedCard?.spriteId == card.spriteId}")
        return selectedCard?.cardId != card.cardId && selectedCard?.spriteId == card.spriteId
    }

    private fun incrementAttemptCount(): Int {
        attemptCount++
        return attemptCount
    }

    private fun broadcastFlipCount() {
        flippedCardCount.onNext(flippedCards.size)
    }

    private fun broadcastGameState(state: GameState) {
        gameState.onNext(state)
    }

    private fun delayedAction(function: () -> Unit) {
        disposables.add(
                Completable.timer(ONE_AND_A_HALF_SECONDS.toLong(), TimeUnit.MILLISECONDS)
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe { function() })
    }

    companion object {
        private const val ONE_AND_A_HALF_SECONDS = 600
    }

    override fun onCleared() {
        super.onCleared()
        DEBUG("ON CLEARED CALLED")
        clearDisposables()
    }

    private fun clearDisposables() {
        DEBUG("Clearing Disposables")
        disposables.dispose()
        DEBUG("Disposables cleared : ${disposables.isDisposed}")
    }

}