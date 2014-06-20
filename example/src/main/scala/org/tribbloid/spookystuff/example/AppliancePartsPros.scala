package org.tribbloid.spookystuff.example

import org.apache.spark.{SparkContext, SparkConf}
import org.tribbloid.spookystuff.SpookyContext._
import org.tribbloid.spookystuff.entity._
import scala.collection.JavaConversions._
import java.io.Serializable

/**
 * Created by peng on 07/06/14.
 */
object AppliancePartsPros {

  def main(args: Array[String]) {
    val conf = new SparkConf().setAppName("MoreLinkedIn")
    conf.setMaster("local[8,3]")
    //    conf.setMaster("local-cluster[2,4,1000]")
    conf.setSparkHome(System.getenv("SPARK_HOME"))
    val jars = SparkContext.jarOfClass(this.getClass).toList
    conf.setJars(jars)
    conf.set("spark.task.maxFailures", "3")
    val sc = new SparkContext(conf)

    (sc.parallelize(Seq("A210S")) +>
      Visit("http://www.appliancepartspros.com/") +>
      TextInput("input.ac-input","#{_}") +>
      Click("input[value=\"Search\"]") +> //TODO: can't use Submit, why?
      Delay(10) ! //TODO: change to DelayFor to save time
      ).selectInto(
        "model" -> { _.text1("div.dgrm-lst div.header h2") },
        "time1" -> { _.backtrace.last.timeline.asInstanceOf[Serializable] } //ugly tail
      ).wgetJoin("div.inner li a:has(img)")
      .selectInto("schematic" -> {_.text1("div#ctl00_cphMain_up1 h1 span")})
      .wgetJoin("tbody.m-bsc td.pdct-descr h2 a")
      .map(
        page => (
          page.context.get("_"),
          page.context.get("time1"),
          page.context.get("model"),
          page.context.get("schematic"),
          page.text1("div.m-pdct h1"),
          page.text1("div.m-pdct td[itemprop=\"brand\"] span"),
          page.text1("div.m-bsc div.mod ul li:contains(Manufacturer) strong"),
          page.text1("div.m-pdct div.m-chm p")
          )
      ).collect().foreach(println(_))
  }
}
//TODO: some tasks went dead
//TODO: some phantomjs instance doesn't close