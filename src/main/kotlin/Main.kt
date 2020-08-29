import com.github.kittinunf.fuel.core.isSuccessful
import com.github.kittinunf.fuel.httpGet
import com.github.kittinunf.result.Result
import org.jsoup.Jsoup

fun main(args: Array<String>) {
    val yahooRouteInfoParser = YahooRouteInfoParser()

    for (station in yahooRouteInfoParser.getStationList("大阪")){
        for(direction in yahooRouteInfoParser.getDirectionFromUrl(station.value)){
            yahooRouteInfoParser.getTimeTableInfo(direction.value)
            break
        }
        break
    }
    //yahooRouteInfoParser.getTimeTableInfo("https://transit.yahoo.co.jp/station/time/25853/?kind=1&gid=7190&q=%E5%A4%A7%E9%98%AA&tab=time&done=time")
}

fun getDataAsync(requestUrl: String) {
    val getAsync = requestUrl.httpGet().response {request, response, result ->
        when(result){
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

class YahooRouteInfoParser {

    /**
     * 時刻情報
     */
    data class TimeInfo (
        var time: String = "-1",
        var type: String = "取得失敗",
        var direction: String = "取得失敗"
    )

    // 路線検索URLベース部分
    private val YAHOO_ROUTE_SEARCH_BASE_URL = "https://transit.yahoo.co.jp"

    // キー分割文字
    private val KEY_DELIMITER_STR = "///"

    /**
     * 駅リスト取得
     * @param stationName 検索対象駅名
     * @return key：駅名, value:URL
     */
    fun getStationList(stationName: String): Map<String, String> {
        // 駅名検索結果を取得
        val requestUrl = "${YAHOO_ROUTE_SEARCH_BASE_URL}/station/time/search?srtbl=on&kind=1&done=time&q=$stationName"
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
            stationList[element.text()] = YAHOO_ROUTE_SEARCH_BASE_URL + element.attr("href").toString()
        }
        return stationList
    }

    /**
     * 行先リスト取得
     * @param stationName 検索対象駅名
     * @return key：路線名///行先, value:URL
     */
    fun getDirectionFromStationName(stationName: String): Map<String, String> {
        val requestUrl = "${YAHOO_ROUTE_SEARCH_BASE_URL}/station/time/search?srtbl=on&kind=1&done=time&q=$stationName"
        return getDirectionFromUrl(requestUrl)
    }

    /**
     * 行先リスト取得
     * @param stationUrl 取得先URL
     * @return key：路線名///行先, value:URL
     */
    fun getDirectionFromUrl(stationUrl: String): Map<String, String> {
        // 検索結果取得
        val directionList = mutableMapOf<String, String>()
        val document = getHTMLDocument(stationUrl) ?: return directionList

        // 行先一覧箇所を取得
        val searchResultDiv = document.getElementById("mdSearchLine") ?: return directionList
        val directionListRootElement = searchResultDiv.getElementsByClass("elmSearchItem")
        if (directionListRootElement.size < 1) {
            return directionList
        }
        val directionElementsRoot = directionListRootElement[0].select("li > dl")

        // 路線名，行先，URLを取得
        for (element in directionElementsRoot) {
            val routeNameElements = element.select("dl > dt")
            val linkElements = element.select("li > a")
            if (routeNameElements.size > 0 && linkElements.size > 0) {
                // 路線名，行先をキーとする
                val key = routeNameElements[0].text() + KEY_DELIMITER_STR + linkElements[0].text()
                directionList[key] = YAHOO_ROUTE_SEARCH_BASE_URL + linkElements.attr("href").toString()
            }
        }
        return directionList
    }

    /**
     * 行先データキー分割
     * @param keyString キー文字列
     * @return <路線名, 行先>
     */
    fun splitDirectionKey(keyString: String): Pair<String, String>{
        val splitStrings = keyString.split(KEY_DELIMITER_STR)
        if(splitStrings.size >= 2){
            return Pair(splitStrings[0], splitStrings[1])
        }
        return Pair("", "")
    }

    /**
     * 時刻表情報取得
     * @param timeTableUrl 時刻表ページURL
     * @return 時刻データ([平日データリスト,土曜データリスト,日曜・祝日データリスト])
     */
    fun getTimeTableInfo(timeTableUrl: String): List<List<TimeInfo>> {
        // 平日，土曜，日曜・祝日分のURLを取得
        val tableUrls = getTimeTableUrlList(timeTableUrl)

        val timeTableInfoList = mutableListOf<List<TimeInfo>>()
        for (tableUrl in tableUrls){
            // 詳細ページへのURLを取得し，全ページ分解析実行
            val detailUrls = getTimeDetailsUrlList(tableUrl)
            val timeInfoList = mutableListOf<TimeInfo>()
            for (detailUrl in detailUrls) {
                // 解析して結果を保持
                timeInfoList.add(getTimeInfo(detailUrl))
            }
            timeTableInfoList.add(timeInfoList)
        }

        return timeTableInfoList
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
        val dateInfoElements= dateSwitchElements[0].select("li > a")
        for (element in dateInfoElements) {
            timeTableUrls.add(YAHOO_ROUTE_SEARCH_BASE_URL + element.attr("href").toString())
        }

        return timeTableUrls
    }

    /**
     * 時刻詳細URLリスト取得
     * @param tableUrl 時刻表URL
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
                urlList.add(YAHOO_ROUTE_SEARCH_BASE_URL + element.attr("href").toString())
            }
        }
        return urlList
    }

    /**
     * 時刻情報取得
     * @param timeInfoDetailUrl 時刻詳細ページURL
     * @return 時刻情報
     */
    private fun getTimeInfo(timeInfoDetailUrl: String): TimeInfo {
        // データ取得
        val timeInfo = TimeInfo()
        val document = getHTMLDocument(timeInfoDetailUrl) ?: return timeInfo

        // 時刻情報部分取得
        val timeInfoRootElement = document.getElementById("mdDiaStopSta") ?: return timeInfo
        val headerElements = timeInfoRootElement.select(".labelMedium > .title")
        val detailElements = timeInfoRootElement.getElementsByClass("txtTrainInfo")
        if (headerElements.size < 1 || detailElements.size < 1) {
            return timeInfo
        }
        val headerTexts = headerElements[0].text().split(" |　".toRegex())
        val detailTexts = detailElements[0].text().split(" |　".toRegex())

        // 文字列からTimeInfo生成
        if(detailTexts.size >= 5) {
            // HH:MM形式に合わせるため，2文字目にコロンがあれば0追加
            timeInfo.time = when{
                (detailTexts[0].substring(1,2) == ":") -> "0" + detailTexts[0]
                else -> detailTexts[0]
            }
            timeInfo.type = detailTexts[3]
        }
        if(headerTexts.size >= 3){
            val splitText = headerTexts[1].split("→|行き".toRegex())
            if(splitText.size >= 2) {
                timeInfo.direction = splitText[1]
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
            val syncResponse = url.httpGet().response()
            if (syncResponse.second.isSuccessful) {
                return Jsoup.parse(String(syncResponse.second.data))
            }
            else{
                retryCount++
                Thread.sleep(1000)
                // android
                //Handler().postDelayed({}, 1000)
            }
        } while (retryCount <= 10)
        return null
    }
}
