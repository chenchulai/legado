package io.legado.app.ui.changesource

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import io.legado.app.App
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.WebBook
import io.legado.app.utils.getPrefBoolean
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.asCoroutineDispatcher
import org.jetbrains.anko.debug
import java.util.concurrent.Executors

class ChangeSourceViewModel(application: Application) : BaseViewModel(application) {
    private var searchPool =
        Executors.newFixedThreadPool(AppConfig.threadCount).asCoroutineDispatcher()
    val searchStateData = MutableLiveData<Boolean>()
    val searchBooksLiveData = MutableLiveData<List<SearchBook>>()
    var name: String = ""
    var author: String = ""
    private var task: Coroutine<*>? = null
    private var screenKey: String = ""
    private val searchBooks = linkedSetOf<SearchBook>()

    fun initData(arguments: Bundle?) {
        arguments?.let { bundle ->
            bundle.getString("name")?.let {
                name = it
            }
            bundle.getString("author")?.let {
                author = it
            }
        }
    }

    fun loadDbSearchBook() {
        execute {
            App.db.searchBookDao().getByNameAuthorEnable(name, author).let {
                searchBooks.addAll(it)
                upAdapter()
            }
        }
    }

    private fun upAdapter() {
        execute {
            val books = searchBooks.toList()
            searchBooksLiveData.postValue(books.sortedBy { it.originOrder })
        }
    }

    private fun searchFinish(searchBook: SearchBook) {
        App.db.searchBookDao().insert(searchBook)
        if (screenKey.isEmpty()) {
            searchBooks.add(searchBook)
        } else if (searchBook.originName.contains(screenKey)) {
            searchBooks.add(searchBook)
        }
        upAdapter()
    }

    fun search() {
        task = execute {
            searchStateData.postValue(true)
            val bookSourceList = App.db.bookSourceDao().allEnabled
            for (item in bookSourceList) {
                //task取消时自动取消 by （scope = this@execute）
                WebBook(item).searchBook(name, scope = this@execute, context = searchPool)
                    .timeout(30000L)
                    .onSuccess(IO) {
                        it?.forEach { searchBook ->
                            if (searchBook.name == name && searchBook.author == author) {
                                if (context.getPrefBoolean(PreferKey.changeSourceLoadToc)) {
                                    if (searchBook.tocUrl.isEmpty()) {
                                        loadBookInfo(searchBook.toBook())
                                    } else {
                                        loadChapter(searchBook.toBook())
                                    }
                                } else {
                                    searchFinish(searchBook)
                                }
                                return@onSuccess
                            }
                        }
                    }
            }
        }

        task?.invokeOnCompletion {
            searchStateData.postValue(false)
        }
    }

    private fun loadBookInfo(book: Book) {
        execute {
            App.db.bookSourceDao().getBookSource(book.origin)?.let { bookSource ->
                WebBook(bookSource).getBookInfo(book, this)
                    .onSuccess {
                        it?.let { loadChapter(it) }
                    }.onError {
                        debug { context.getString(R.string.error_get_book_info) }
                    }
            } ?: debug { context.getString(R.string.error_no_source) }
        }
    }

    private fun loadChapter(book: Book) {
        execute {
            App.db.bookSourceDao().getBookSource(book.origin)?.let { bookSource ->
                WebBook(bookSource).getChapterList(book, this)
                    .onSuccess(IO) {
                        it?.let { chapters ->
                            if (chapters.isNotEmpty()) {
                                book.latestChapterTitle = chapters.last().title
                                val searchBook: SearchBook = book.toSearchBook()
                                searchFinish(searchBook)
                            }
                        }
                    }.onError {
                        debug { context.getString(R.string.error_get_chapter_list) }
                    }
            } ?: debug { R.string.error_no_source }
        }
    }

    /**
     * 筛选
     */
    fun screen(key: String?) {
        execute {
            screenKey = key ?: ""
            if (key.isNullOrEmpty()) {
                loadDbSearchBook()
            } else {
                val items = App.db.searchBookDao().getChangeSourceSearch(name, author, screenKey)
                searchBooks.clear()
                searchBooks.addAll(items)
                upAdapter()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchPool.close()
    }

}