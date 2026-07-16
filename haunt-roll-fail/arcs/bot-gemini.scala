package arcs

import hrf.colmat._
import hrf.compute._
import hrf.logger._
import org.scalajs.dom

object AI {
    var enabled : Boolean = false
}

object ManualRoll {
    var enabled : Boolean = false
}

class FetchCompute(prompt: String) extends Compute[String] {
    var result: Option[String] = None

    def get(continue: (() => Unit) => Unit)(onResult: String => Unit): Unit = {
        result match {
            case Some(r) => onResult(r)
            case None =>
                val headersObj = new dom.Headers()
                headersObj.append("Content-Type", "text/plain")
                
                val controller = scala.scalajs.js.Dynamic.newInstance(scala.scalajs.js.Dynamic.global.AbortController)()
                val signal = controller.signal
                
                scala.scalajs.js.timers.setTimeout(6000) {
                    controller.abort()
                }
                
                val requestInit = new dom.RequestInit {
                    method = dom.HttpMethod.POST
                    body = prompt
                    headers = headersObj
                }
                
                requestInit.asInstanceOf[scala.scalajs.js.Dynamic].signal = signal
                
                dom.window.fetch("/api/gemini/decide", requestInit)
                  .asInstanceOf[scala.scalajs.js.Dynamic]
                  .then((response: scala.scalajs.js.Dynamic) => response.text())
                  .then((text: String) => {
                      result = Some(text)
                      continue(() => onResult(text))
                  })
                  .asInstanceOf[scala.scalajs.js.Dynamic].`catch`((err: scala.scalajs.js.Any) => {
                      result = Some("")
                      continue(() => onResult(""))
                  })
        }
    }
}

class BotGemini(val self: Faction, val fallback: EvalBot) extends EvalBot {
    def eval(actions : $[UserAction])(implicit game : Game) : Compute[$[ActionEval]] = {
        if (actions.num <= 1) {
            return fallback.eval(actions)
        }

        val isStrategic = actions.exists { a =>
            val unwrapped = a.unwrap
            unwrapped.isInstanceOf[LeadAction] ||
            unwrapped.isInstanceOf[SurpassAction] ||
            unwrapped.isInstanceOf[CopyAction] ||
            unwrapped.isInstanceOf[PivotAction] ||
            unwrapped.isInstanceOf[DeclareAmbitionAction] ||
            unwrapped.isInstanceOf[ChooseEdictsAction] ||
            unwrapped.isInstanceOf[InfluenceAction] ||
            unwrapped.isInstanceOf[SecureAction]
        }

        if (!isStrategic) {
            return fallback.eval(actions)
        }

        val prompt = constructPrompt(self, actions)

        new FetchCompute(prompt).flatMap { responseText =>
            val chosenIndex = parseIndex(responseText, actions.num)
            if (chosenIndex >= 0 && chosenIndex < actions.num) {
                val chosenAction = actions(chosenIndex)
                Just(actions./{ a => ActionEval(a, if (a == chosenAction) $(Evaluation(10000000, "gemini")) else $) })
            } else {
                fallback.eval(actions)
            }
        }
    }

    private def constructPrompt(f: Faction, actions: $[UserAction])(implicit game: Game): String = {
        val sb = new java.lang.StringBuilder()
        sb.append("Estás jugando al juego de mesa Arcs.\n")
        sb.append(s"Tu facción: ${f.name}\n")
        sb.append(s"Capítulo actual: ${game.chapter}, Ronda: ${game.round}\n")
        sb.append("Puntuaciones de las facciones:\n")
        game.factions.foreach(fac => sb.append(s"- ${fac.name}: ${fac.power} puntos\n"))
        
        sb.append("\nAcciones estratégicas disponibles (elige una respondiendo SOLO con su número):\n")
        actions.indexed.foreach { case (a, idx) =>
            sb.append(s"$idx: ${a.unwrap.toString}\n")
        }
        sb.append("\nResponde ÚNICAMENTE con el número de la acción elegida (por ejemplo, \"0\"). No agregues ninguna explicación ni texto adicional.")
        sb.toString
    }

    private def parseIndex(response: String, max: Int): Int = {
        try {
            val parsed = scala.scalajs.js.JSON.parse(response).asInstanceOf[scala.scalajs.js.Dynamic]
            val text = parsed.candidates(0).content.parts(0).text.asInstanceOf[String].trim
            val pattern = """\d+""".r
            val numStr = pattern.findFirstIn(text).getOrElse("-1")
            val idx = numStr.toInt
            if (idx >= 0 && idx < max) idx else -1
        } catch {
            case _: Throwable => -1
        }
    }
}
