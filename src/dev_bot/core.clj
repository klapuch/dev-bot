(ns dev-bot.core
  (:use [clojure.java.shell :only [sh]])
  (:require [clj-http.client :as client])
  (:require [cheshire.core :as json])
  (:gen-class))

(defn -main
  [& args]
  (def config (load-file "config.edn"))
  (def http-settings (load-file "http-settings.edn"))

  (defn run-shell-cmd [command] (sh "sh" "-c" command))

  (defn markdown-text [text] (format "```%s```" text))

;  (sh run-shell-cmd (format "git checkout %s" (:fix-branch config)))

  (defn send-pull-request! [body title] (print body))
  (defn create-issue!
    [body title]
    (let [{url :url headers :headers issue-path :issue-path} http-settings]
      (client/post
       (format "%s/%s" url issue-path)
       {:headers headers
        :content-type :json
        :body (json/generate-string {:title title :body (markdown-text body)})}
       )
    ))

  (defn check! [name action]
    (let [
           {cmd :command title :title} (name (:commands config))
           {exit :exit output :out} (run-shell-cmd cmd)
           ]
      (if
       (= exit 0) (action output title))
  ))

  (check! :phpstan create-issue!)

  (shutdown-agents)
)
