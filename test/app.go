package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
)

func handler(w http.ResponseWriter, r *http.Request) {
	fmt.Fprintf(w,
		"BUILD_VALUE=%s, TEMPLATE_VALUE=%s, SECRET_VALUE=%s, HELM_VALUE=%s",
		os.Getenv("BUILD_VALUE"),
		os.Getenv("TEMPLATE_VALUE"),
		os.Getenv("TEST_SECRET"),
		os.Getenv("HELM_VALUE"))
}

func main() {
	http.HandleFunc("/", handler)
	log.Fatal(http.ListenAndServe(":80", nil))
}
