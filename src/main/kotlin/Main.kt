import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.core.isSuccessful
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.result.Result
import org.jsoup.Jsoup
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.*
import java.util.concurrent.locks.Lock
import kotlin.coroutines.CoroutineContext

suspend fun main(args: Array<String>) {
    val yahooRouteInfoGetter = YahooRouteInfoGetter()

    var list = listOf(listOf<YahooRouteInfoGetter.TimeInfo>())
    var maxCount = 0
    var currentCount = 0
    val lock = java.util.concurrent.locks.ReentrantLock()
    for (station in yahooRouteInfoGetter.getStationList("大阪")) {
        for (direction in yahooRouteInfoGetter.getDestinationFromUrl(station.value)) {
            list = yahooRouteInfoGetter.getTimeTableInfo(direction.value,
                {
                    lock.lock()
                    try {
                        maxCount += it
                    }
                    finally {
                        lock.unlock()
                    }
                },
                {
                    lock.lock()
                    try {
                        currentCount++
                    }
                    finally {
                        lock.unlock()
                    }
                })
            break
        }
        break
    }
    println(list)
    //yahooRouteInfoGetter.getTimeTableInfo("https://transit.yahoo.co.jp/station/time/25853/?kind=1&gid=7190&q=%E5%A4%A7%E9%98%AA&tab=time&done=time")
}

fun getDataAsync(requestUrl: String) {
    val getAsync = requestUrl.httpGet().response { request, response, result ->
        when (result) {
            is Result.Success -> {
                println(String(response.data))
            }
            else -> {
                println("取得失敗")
            }
        }
    }
    getAsync.join()
}

class YahooRouteInfoGetter : CoroutineScope {

    /**
     * 時刻情報
     */
    data class TimeInfo(
        // 時刻(HH:MM)
        var time: String = "-1",
        // 種別(普通，快速，etc...)
        var type: String = "取得失敗",
        // 行先
        var destination: String = "取得失敗"
    )

    companion object {
        // キー分割文字
        const val KeyDelimiterSir = "::"

        // 路線検索URLベース部分
        const val YahooRouteSearchBaseUrl = "https://transit.yahoo.co.jp"
    }

    // リクエスト総数
    private var _totalRequestCount = 0

    // 前回のリクエスト実施時刻
    private var _prevRequestDatetime = LocalTime.now()

    // ロック用
    private val _lock = Any()

    // 本フラグメント用job
    private val job = Job()

    // 本スコープ用のコンテキスト
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    init {
        FuelManager.instance.baseHeaders =
            mapOf("User-Agent" to "Mozilla/5.0 (twitter:@natsuyasai7)")
    }

    /**
     * dispose
     */
    fun dispose() {
        job.cancel()
    }

    /**
     * 駅リスト取得
     * @param stationName 検索対象駅名
     * @return key：駅名, value:URL
     */
    fun getStationList(stationName: String): Map<String, String> {
        // 駅名検索結果を取得
        val requestUrl =
            "${YahooRouteSearchBaseUrl}/station/time/search?srtbl=on&kind=1&done=time&q=$stationName"
        val stationList = mutableMapOf<String, String>()
        val document = getHTMLDocument(requestUrl) ?: return stationList
        // 駅一覧箇所を取得
        val searchResultDiv = document.getElementById("mdSearchResult") ?: return stationList
        val stationListRootElement = searchResultDiv.getElementsByClass("elmSearchItem")
        if (stationListRootElement.size < 1) {
            return stationList
        }
        // 駅名と遷移先URLをmapにつめる
        val stationInfoElements = stationListRootElement[0].select("li > a")
        for (element in stationInfoElements) {
            stationList[element.text()] =
                YahooRouteSearchBaseUrl + element.attr("href").toString()
        }
        return stationList
    }

    /**
     * 行先リスト取得
     * @param stationName 検索対象駅名
     * @return key：路線名///行先, value:URL
     */
    fun getDestinationFromStationName(stationName: String): Map<String, String> {
        val requestUrl =
            "${YahooRouteSearchBaseUrl}/station/time/search?srtbl=on&kind=1&done=time&q=$stationName"
        return getDestinationFromUrl(requestUrl)
    }

    /**
     * 行先リスト取得
     * @param stationUrl 取得先URL
     * @return key：路線名///行先, value:URL
     */
    fun getDestinationFromUrl(stationUrl: String): Map<String, String> {
        // 検索結果取得
        val destinationList = mutableMapOf<String, String>()
        val document = getHTMLDocument(stationUrl) ?: return destinationList

        // 行先一覧箇所を取得
        val searchResultDiv = document.getElementById("mdSearchLine") ?: return destinationList
        val destinationListRootElement = searchResultDiv.getElementsByClass("elmSearchItem")
        if (destinationListRootElement.size < 1) {
            return destinationList
        }
        val destinationElementsRoot = destinationListRootElement[0].select("li > dl")

        // 路線名，行先，URLを取得
        for (element in destinationElementsRoot) {
            val routeNameElements = element.select("dl > dt")
            val linkElements = element.select("li > a")
            if (routeNameElements.size > 0 && linkElements.size > 0) {
                for (linkElement in linkElements) {
                    // 路線名，行先をキーとする
                    val key = routeNameElements[0].text() + KeyDelimiterSir + linkElement.text()
                    destinationList[key] =
                        YahooRouteSearchBaseUrl + linkElement.attr("href").toString()
                }
            }
        }
        return destinationList
    }

    /**
     * 行先データキー分割
     * @param keyString キー文字列
     * @return <路線名, 行先>
     */
    fun splitDestinationKey(keyString: String): Pair<String, String> {
        val splitStrings = keyString.split(KeyDelimiterSir)
        if (splitStrings.size >= 2) {
            return Pair(splitStrings[0], splitStrings[1])
        }
        return Pair("", "")
    }

    /**
     * 時刻表情報取得
     * @param timeTableUrl 時刻表ページURL(平日分)
     * @return 時刻データ([平日データリスト,土曜データリスト,日曜・祝日データリスト])
     */
    suspend fun getTimeTableInfo(
        timeTableUrl: String,
        notifyMaxCountCallback: ((Int) -> Unit),
        notifyCountCallback: (() -> Unit)
    ): List<List<TimeInfo>> {
        // 平日，土曜，日曜・祝日分のURLを取得
        val tableUrls = getTimeTableUrlList(timeTableUrl)
        var timeTableInfoList = listOf<List<TimeInfo>>()
        if (tableUrls.size == 3) {
            coroutineScope {
                val awaitList = listOf(
                    async { getTimeInfoList(tableUrls[0], notifyMaxCountCallback, notifyCountCallback) },
                    async { getTimeInfoList(tableUrls[1], notifyMaxCountCallback, notifyCountCallback) },
                    async { getTimeInfoList(tableUrls[2], notifyMaxCountCallback, notifyCountCallback) })
                timeTableInfoList = awaitList.awaitAll()
            }
        }

        return timeTableInfoList
    }

    /**
     * 時刻情報リスト取得
     * @param tableUrl 時刻表ページURL
     * @return 時刻情報情報
     */
    private fun getTimeInfoList(
        tableUrl: String,
        notifyMaxCountCallback: ((Int) -> Unit),
        notifyCountCallback: (() -> Unit)
    ): List<TimeInfo> {
        // 詳細ページへのURLを取得し，全ページ分解析実行
        val detailUrls = getTimeDetailsUrlList(tableUrl)
        notifyMaxCountCallback(detailUrls.count())
        val timeInfoList = mutableListOf<TimeInfo>()
        for (detailUrl in detailUrls) {
            // 解析して結果を保持
            val info: TimeInfo = getTimeInfo(detailUrl) ?: return mutableListOf()
            timeInfoList.add(info)
            notifyCountCallback()
        }
        if (timeInfoList.count() != detailUrls.count()) {
            // 件数が一致しないため失敗
            return mutableListOf()
        }
        return timeInfoList
    }

    /**
     * 時刻表URLリスト取得
     * @param timeTableUrl 時刻表ページURL
     * @return 時刻表URL(平日,土曜,日曜・祝日)
     */
    private fun getTimeTableUrlList(timeTableUrl: String): List<String> {
        val timeTableUrls = mutableListOf<String>()
        val document = getHTMLDocument(timeTableUrl) ?: return timeTableUrls

        // 曜日切り替え箇所から，3種の時刻表URL取得
        val timeTableRootElement = document.getElementById("mdStaLineDia") ?: return timeTableUrls
        val dateSwitchElements = timeTableRootElement.getElementsByClass("navDayOfWeek")
        if (dateSwitchElements.size < 1) {
            return timeTableUrls
        }
        // 平日は引数でもらうURLのため，そのまま保持
        timeTableUrls.add(timeTableUrl)
        val dateInfoElements = dateSwitchElements[0].select("li > a")
        for (element in dateInfoElements) {
            timeTableUrls.add(YahooRouteSearchBaseUrl + element.attr("href").toString())
        }

        return timeTableUrls
    }

    /**
     * 時刻詳細URLリスト取得
     * @param timeTableUrl 時刻表URL
     * @return 時刻情報詳細ページURLリスト
     */
    private fun getTimeDetailsUrlList(timeTableUrl: String): List<String> {
        // データ取得
        val urlList = mutableListOf<String>()
        val document = getHTMLDocument(timeTableUrl) ?: return urlList

        // 時刻情報部分取得
        val timeTableRootElement = document.getElementById("mdStaLineDia") ?: return urlList
        val timeTableRootElements = timeTableRootElement.getElementsByClass("tblDiaDetail")
        if (timeTableRootElements.size < 1) {
            return urlList
        }
        // 時刻表の1セル部分内のaタグから詳細ページへのリンクを取得
        val timeTableCells = timeTableRootElements[0].getElementsByClass("timeNumb")
        for (cell in timeTableCells) {
            for (element in cell.select("a")) {
                urlList.add(YahooRouteSearchBaseUrl + element.attr("href").toString())
            }
        }
        return urlList
    }

    /**
     * 時刻情報取得
     * @param timeInfoDetailUrl 時刻詳細ページURL
     * @return 時刻情報
     */
    private fun getTimeInfo(timeInfoDetailUrl: String): TimeInfo? {
        // データ取得
        val timeInfo = TimeInfo()
        val document = getHTMLDocument(timeInfoDetailUrl) ?: return null

        // 時刻情報部分取得
        val timeInfoRootElement = document.getElementById("mdDiaStopSta") ?: return timeInfo
        val headerElements = timeInfoRootElement.select(".labelMedium > .title")
        val detailElements = timeInfoRootElement.getElementsByClass("txtTrainInfo")
        if (headerElements.size < 1 || detailElements.size < 1) {
            return null
        }
        val headerTexts = headerElements[0].text().split("[ 　]".toRegex())
        val detailTexts = detailElements[0].text().split("[ 　]".toRegex())

        // 文字列からTimeInfo生成
        if (detailTexts.size >= 5) {
            // HH:MM形式に合わせるため，2文字目にコロンがあれば0追加
            timeInfo.time = when {
                (detailTexts[0].substring(1, 2) == ":") -> "0" + detailTexts[0]
                else -> detailTexts[0]
            }
            timeInfo.type = detailTexts[3]
        }
        if (headerTexts.size >= 3) {
            val splitText = headerTexts[1].split("→|行き".toRegex())
            if (splitText.size >= 2) {
                timeInfo.destination = splitText[1]
            }
        }

        return timeInfo
    }

    /**
     * HTMLドキュメント取得
     * @param url 取得対象URL
     * @return HTMLドキュメントルート情報
     */
    private fun getHTMLDocument(url: String): org.jsoup.nodes.Document? {
        var retryCount = 0
        do {
            tryWait()
            val syncResponse = url.httpGet().response()
            if (syncResponse.second.isSuccessful) {
                return Jsoup.parse(String(syncResponse.second.data))
            } else {
                retryCount++
                Thread.sleep(3000)
            }
        } while (retryCount <= 10)
        return null
    }

    /**
     * 待ち試し
     */
    private fun tryWait() {
        // 一定時間経過していればリセット
        synchronized(_lock) {
            if (_totalRequestCount != 0
                && ChronoUnit.SECONDS.between(_prevRequestDatetime, LocalTime.now()) > 30
            ) {
                _totalRequestCount = 0
            }

            // 一定期間未満かつ2回目以降のリクエストなら少し待つ
            if (_totalRequestCount > 5) {
                Thread.sleep(500)
            }
            _totalRequestCount++
            _prevRequestDatetime = LocalTime.now()
        }
    }
}