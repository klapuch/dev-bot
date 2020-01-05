(ns dev-bot.core
  (:use [clojure.java.shell :only [sh]])
  (:require [clj-http.client :as client])
  (:require [cheshire.core :as json])
  (:require [clojure.string :as str])
  (:gen-class))

(defn -main
  [& args]


  ;; utils functions
  (def not-empty? (complement empty?))
  (defn to-query-params
    [params]
    (str/join "&" (map #(str %1 "=" %2) (keys params) (vals params)))
  )
  (defn run-shell-cmd [command] (sh "sh" "-c" command))


  ;; load configs
  (def config (load-file "config.edn"))
  (def http-settings (load-file "http-settings.edn"))

  (defn code-text [text] (format "```%s```" text))

  (def issues-url
    (let [{url :url issue-path :issue-path} http-settings]
      (format "%s/%s" url issue-path)
  ))

;  (sh run-shell-cmd (format "git checkout %s" (:fix-branch config)))

  (defn send-pull-request! [body title] (print body))

  (defn list-my-issues
    []
    (let [{body :body} (client/get (format "%s?%s" issues-url (to-query-params {"filter" "created" "state" "open"})))]
      (json/parse-string body true)
  ))

  (defn issue-created?
    [issues name]
    (not-empty?
      (let [{title :title} (name (:commands config))]
        (->> issues
          (map #(:title %1))
          (map str/trim)
          (filter #(= (str/trim title) %1))
      )))
    )

  (defn create-issue!
    [body title]
    (let [{url :url headers :headers issue-path :issue-path} http-settings]
      (client/post
       issues-url
       {:headers headers
        :content-type :json
        :body (json/generate-string {:title title :body (code-text body)})}
       )
    ))

  (defn check!
    [name action]
    (if-not (issue-created? (list-my-issues) name)
            (let [{cmd :command title :title} (name (:commands config))
                  {exit :exit output :out}    (run-shell-cmd cmd)]
              (if
                (not= exit 0) (action output title))
  )))

  (check! :phpstan create-issue!)
  (check! :phpcs create-issue!)
  (check! :eslint create-issue!)

  (shutdown-agents)
)
