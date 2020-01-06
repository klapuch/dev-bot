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
    (str/join "&" (map #(str/join "=" [%1 %2]) (keys params) (vals params)))
  )
  (defn run-shell-cmd [command] (sh "sh" "-c" command))


  ;; load configs
  (def config (load-file "config.edn"))
  (def http-settings (load-file "http-settings.edn"))

  (defn code-text [text] (format "```%s```" text))

  (defn checkout-branch! [name] (run-shell-cmd (format "git checkout %s" name)))
  (defn checkout-cmd-branch! [name] (checkout-branch! (:branch (name (:commands config)))))

  (defn with-api-path [path] (format "%s/%s" (:url http-settings) path))

  (def issues-url (with-api-path (:issue-path http-settings)))
  (def pull-request-url (with-api-path (:pull-request-path http-settings)))

  (defn format-branch [username branch] (format "%s:%s" username branch))

  (defn prepare-pull-request-branch
    [branch message]
    (let [commands [
      "git checkout master"
      (format "git checkout -b %s" branch)
      "git add -A"
      (format "git commit -m '%s'" message)
      (format "git push origin %s" branch)]]

      (str/join " && " commands)
    ))

  (defn send-pull-request!
    [title branch]
    (let [{url :url headers :headers pull-request-path :pull-request-path user :user} http-settings]
      (do
        (run-shell-cmd (prepare-pull-request-branch branch title))
        (client/post
         pull-request-url
         {:headers headers
          :content-type :json
          :body (json/generate-string {:title title :head (format-branch user branch) :base "master"})}
      ))))

  (defn my-issues
    []
    (let [{body :body} (client/get (format "%s?%s" issues-url (to-query-params {"filter" "created" "state" "open"})))]
      (json/parse-string body true)
  ))

  (defn my-pull-requests
    [name]
    (let [
      {user :user} http-settings
      {branch :branch} (name (:commands config))
      {body :body} (client/get (format "%s?%s" pull-request-url (to-query-params {"head" (format-branch user branch) "state" "open"})))
    ]
      (json/parse-string body true)))

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

  (defn pull-request-created? [pull-requests] (not-empty? pull-requests))

  (defn create-issue!
    [body title]
    (let [{url :url headers :headers issue-path :issue-path} http-settings]
      (do
        (checkout-branch! "master")
        (client/post
         issues-url
         {:headers headers
          :content-type :json
          :body (json/generate-string {:title title :body (code-text body)})}
         )
        )
    ))

  (defn check!
    [name action]
    (if-not
     (and
      (pull-request-created? (my-pull-requests name))
      (issue-created? (my-issues) name))
      (let [{cmd :command title :title} (name (:commands config))
            {exit :exit output :out} (run-shell-cmd cmd)]
      (if (not= exit 0) (action output title)))))


;  (send-pull-request! "test" "bot-phpcs")
  (check! :phpcbf #(send-pull-request! %2 "bot-phpcs"))
  (check! :phpstan create-issue!) ;; ref
  (check! :phpcs create-issue!)
  ;; deref (@) phpstan
  (check! :eslintFix #(send-pull-request! %2 "bot-eslint"))
  (check! :eslint create-issue!)

  (shutdown-agents)
)
