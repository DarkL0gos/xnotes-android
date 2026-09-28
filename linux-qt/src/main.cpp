#include "MainWindow.h"

#include <QApplication>
#include <QMessageBox>

#include <cstdio>

int main(int argc, char** argv) {
    // Pen samples one by one: the core coalesces the repaints, and every sample shapes the ink.
    QApplication::setAttribute(Qt::AA_CompressTabletEvents, false);
    QApplication app(argc, argv);
    QApplication::setApplicationName(QStringLiteral("xnotes"));

    if (xn_api_version() != XN_API_VERSION) {
        std::fprintf(stderr, "libxnotes API %d, expected %d\n", xn_api_version(), XN_API_VERSION);
        return 1;
    }

    MainWindow window;
    const QStringList args = QApplication::arguments();
    if (args.size() < 2 || !window.open(args.at(1))) window.newNote();
    window.show();
    return QApplication::exec();
}
