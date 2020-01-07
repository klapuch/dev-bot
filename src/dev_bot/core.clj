(ns dev-bot.core
  (:use [clojure.java.shell :only [sh]])
  (:require [clj-http.client :as client])
  (:require [cheshire.core :as json])
  (:require [clojure.string :as str])
  (:require [clojure.java.io :as io])
  (:gen-class))

(defn -main
  [& args]

  ;; utils functions
  (def not-empty? (complement empty?))
  (def not-blank? (complement str/blank?))
  (defn to-query-params
    [params]
    (str/join "&" (map #(str/join "=" [%1 %2]) (keys params) (vals params)))
  )
  (defn run-shell-cmd [command] (sh "sh" "-c" command)) ;; TODO: specify dir


  ;; load configs
  (def config (load-file "config.edn")) ;; TODO: __DIR__
  (def http-settings (load-file "http-settings.edn")) ;; TODO: __DIR__

  (defn git-clone [repository dir] (run-shell-cmd (format "git clone --branch=master %s %s" repository dir)))
  (defn code-text [text] (format "```%s```" text))

  (defn checkout-branch! [name] (run-shell-cmd (format "git checkout %s" name)))
  (defn checkout-cmd-branch! [name] (checkout-branch! (:branch (name (:commands config)))))

  (defn with-api-path [path] (format "%s/%s" (:base-url http-settings) path))

  (defn any-changes? [] (not-blank? (:out (run-shell-cmd "git status -s"))))

  (def issues-url
    (with-api-path
      (-> (:issue-path http-settings)
          (str/replace #"\{:user\}" (:user config))
          (str/replace #"\{:repo\}" (:repository-name config))))
    )

  (def pull-request-url
    (with-api-path
     (-> (:pull-request-path http-settings)
         (str/replace #"\{:user\}" (:user config))
         (str/replace #"\{:repo\}" (:repository-name config))))
    )

  (defn format-branch [username branch] (format "%s:%s" username branch))

  (defn my-issues
    []
    (let [{body :body} (client/get (format "%s?%s" issues-url (to-query-params {"filter" "created" "state" "open"})))]
      (json/parse-string body true)
  ))

  (defn my-pull-requests
    [branch]
    (let [
      {user :user} config
      {body :body} (client/get (format "%s?%s" pull-request-url (to-query-params {"head" (format-branch user branch) "state" "open"})))
    ]
      (json/parse-string body true)))

  (defn issue-created?
    [issues title]
    (not-empty?
      (->> issues
        (map #(:title %1))
        (map str/trim)
        (filter #(= (str/trim title) %1))
      ))
    )

  (defn pull-request-created? [pull-requests] (not-empty? pull-requests))

  (defn create-issue!
    [{:keys [output title]}]
    (let [{headers :headers issue-path :issue-path} http-settings]
      (client/post
       issues-url
       {:headers headers
        :content-type :json
        :body (json/generate-string {:title title :body (code-text output)})}
       )
    ))

  (defn send-pull-request! ;; TODO: check for changes
    [{:keys [branch title]}]
    (if (any-changes?)
      (let [
          {headers :headers pull-request-path :pull-request-path} http-settings
          {user :user} config
          commands [
            "git checkout master"
            (format "git checkout -b %s" branch)
            "git add -A"
            (format "git commit -m '%s'" title)
            (format "git push origin %s" branch)
          ]
        ]
        (do
          (run-shell-cmd (str/join " && " commands))
          (client/post
           pull-request-url
           {:headers headers
            :content-type :json
            :body (json/generate-string {:title title :head (format-branch user branch) :base "master"})}
      )))))

  (defn check!
    [name action]
    (let [{title :title branch :branch command :command} (name (:commands config))]
      (if-not
       (and
        (pull-request-created? (my-pull-requests branch))
        (issue-created? (my-issues) title))
        (let [{exit :exit output :out} (run-shell-cmd command)]
          (if (= exit 0) (action {:output output :title title :branch branch}))))))


  (defn init-project
    []
    (let [{dir :clone-dir repository :repository-uri} config]
      (if-not (.exists (io/file dir))
        (git-clone repository dir))))

  (defn prepare-project
    []
    (run-shell-cmd
      (let [commands ["git clean -fd" "git checkout -- ." "git checkout master" "git pull"]]
        (str/join " && " commands)))
    )

  (init-project)
  (prepare-project)
  (check! :composer-outdated create-issue!)
  ;(def composer-outdated (future (check! :composer-outdated create-issue!)))
  (check! :phpcbf send-pull-request!)
  (check! :phpcs create-issue!)
  (check! :phpstan create-issue!)
  (check! :eslint-fix send-pull-request!)
  (check! :eslint create-issue!)
  ; @composer-outdated

  (shutdown-agents)
)
