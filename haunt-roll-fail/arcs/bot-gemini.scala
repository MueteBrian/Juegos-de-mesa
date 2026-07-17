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
            case Some(r) => 
                println("[FetchCompute.get] returning cached result")
                onResult(r)
            case None =>
                println("[FetchCompute.get] Initializing JS fetch helper")
                val jsFetch = scala.scalajs.js.Dynamic.global.eval(
                    """
                    (url, prompt, timeoutMs, onResolve, onReject) => {
                        const controller = new AbortController();
                        const signal = controller.signal;
                        const timer = setTimeout(() => {
                            console.log("[JS fetch] timeout triggered, aborting request");
                            controller.abort();
                        }, timeoutMs);

                        fetch(url, {
                            method: "POST",
                            body: prompt,
                            headers: { "Content-Type": "text/plain" },
                            signal: signal
                        })
                        .then(response => {
                            clearTimeout(timer);
                            console.log("[JS fetch] HTTP response received with status:", response.status);
                            return response.text();
                        })
                        .then(text => {
                            console.log("[JS fetch] Successfully read response body. Length:", text.length);
                            onResolve(text);
                        })
                        .catch(err => {
                            clearTimeout(timer);
                            console.error("[JS fetch] request failed or aborted:", err.toString());
                            onReject(err.toString());
                        });
                    }
                    """
                ).asInstanceOf[scala.scalajs.js.Function5[String, String, Int, scala.scalajs.js.Function1[String, Any], scala.scalajs.js.Function1[String, Any], Any]]

                val successCallback: scala.scalajs.js.Function1[String, Any] = (text: String) => {
                    println("[FetchCompute.get] successCallback invoked")
                    result = Some(text)
                    continue(() => onResult(text))
                }

                val failureCallback: scala.scalajs.js.Function1[String, Any] = (err: String) => {
                    println(s"[FetchCompute.get] failureCallback invoked: $err")
                    result = Some("")
                    continue(() => onResult(""))
                }

                jsFetch("/api/gemini/decide", prompt, 9000, successCallback, failureCallback)
        }
    }
}

class BotGemini(val self: Faction, val fallback: EvalBot) extends EvalBot {
    def eval(actions : $[UserAction])(implicit game : Game) : Compute[$[ActionEval]] = {
        println(s"[BotGemini.eval] Faction: ${self.name}, Actions count: ${actions.num}")
        if (actions.num <= 1) {
            println("[BotGemini.eval] Actions count <= 1, using fallback bot")
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
            println("[BotGemini.eval] Non-strategic actions, using fallback bot")
            return fallback.eval(actions)
        }

        println("[BotGemini.eval] Strategic actions detected, calling Gemini")
        val prompt = constructPrompt(self, actions)

        new FetchCompute(prompt).flatMap { responseText =>
            println(s"[BotGemini.eval] flatMap callback triggered. responseText: $responseText")
            val chosenIndex = parseIndex(responseText, actions.num)
            println(s"[BotGemini.eval] parsed chosenIndex: $chosenIndex")
            if (chosenIndex >= 0 && chosenIndex < actions.num) {
                val chosenAction = actions(chosenIndex)
                println(s"[BotGemini.eval] chosen action: ${chosenAction.unwrap.toString}")
                Just(actions./{ a => ActionEval(a, if (a == chosenAction) $(Evaluation(10000000, "gemini")) else $) })
            } else {
                println("[BotGemini.eval] Index out of range or parsing failed, using fallback bot")
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
