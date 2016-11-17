(ns redgenes.routes
  (:require-macros [secretary.core :refer [defroute]])
  (:import goog.History)
  (:require [secretary.core :as secretary]
            [accountant.core :as accountant]
            [goog.events :as events]
            [goog.history.EventType :as EventType]
            [re-frame.core :as re-frame]))

(defn hook-browser-navigation! []
  (doto (History.)
    (events/listen
      EventType/NAVIGATE
      (fn [event]
        (accountant/navigate! (str "#" (.-token event)))))
    (.setEnabled true)))

(defn app-routes []
  (secretary/set-config! :prefix "#")
  ;; --------------------
  ;; define routes here
  (defroute "/" []
            (re-frame/dispatch [:set-active-panel :home-panel]))

  (defroute "/about" []
            (re-frame/dispatch [:set-active-panel :about-panel]))

  (defroute "/debug" []
            (re-frame/dispatch [:set-active-panel :debug-panel]))

  (defroute "/help" []
            (re-frame/dispatch [:set-active-panel :help-panel]))

  (defroute "/templates" []
            (re-frame/dispatch [:set-active-panel :templates-panel]))

  (defroute "/upload" []
            (re-frame/dispatch [:set-active-panel :upload-panel]))

  (defroute "/explore" []
            (re-frame/dispatch [:set-active-panel :explore-panel]))

  (defroute "/search" []
            (re-frame/dispatch [:set-active-panel :search-panel]))

  (defroute "/listanalysis" []
            (re-frame/dispatch [:set-active-panel :list-analysis-panel]))

  (defroute "/listanalysis/temp/:name" [name]
            (re-frame/dispatch [:set-active-panel :list-analysis-panel
                                {:temp name}
                                [:listanalysis/run-all {:temp name}]]))

  (defroute "/querybuilder" []
            (re-frame/dispatch [:set-active-panel :querybuilder-panel
                                nil
                                [:query-builder/make-tree]]))

  (defroute "/results" []
            (re-frame/dispatch [:set-active-panel :results-panel]))

  (defroute "/regions" []
            (re-frame/dispatch [:set-active-panel :regions-panel]))

  ;(defroute "/saved-data" []
  ;          (re-frame/dispatch [:set-active-panel :saved-data-panel]))
  (defroute "/saved-data" []
            (re-frame/dispatch [:set-active-panel :saved-data-panel]))

  (defroute "/reportpage/:mine/:type/:id" [mine type id]
            (re-frame/dispatch [:set-active-panel :reportpage-panel
                                {:type type :id id :mine mine}
                                [:load-report mine type id]]))

  ;; --------------------

  (accountant/configure-navigation!
    {:nav-handler  (fn [path] (secretary/dispatch! path))
     :path-exists? (fn [path] (secretary/locate-route path))})

  (hook-browser-navigation!))
