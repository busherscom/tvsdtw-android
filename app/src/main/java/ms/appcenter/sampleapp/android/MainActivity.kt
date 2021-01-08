package ms.appcenter.sampleapp.android

import android.os.Bundle
import android.util.Log
import androidx.annotation.IntRange
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager
import com.microsoft.appcenter.AppCenter
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes
import com.microsoft.appcenter.distribute.Distribute

class MainActivity : AppCompatActivity() {
    private var mSectionsPagerAdapter: SectionsPagerAdapter? = null
    private var mViewPager: ViewPager? = null
    private val views = arrayOf(
            WelcomeActivity(),
            BuildActivity(),
            TestActivity(),
            DistributeActivity(),
            CrashesActivity(),
            AnalyticsActivity(),
            PushActivity()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_root)

        // Initialize SDK
        if (BuildConfig.APPCENTER_APP_SECRET != "") {
            // Use APPCENTER_APP_SECRET environment variable if it exists
            AppCenter.start(application, BuildConfig.APPCENTER_APP_SECRET,
                    Analytics::class.java, Crashes::class.java, Distribute::class.java)
        } else {
            // Otherwise use the hardcoded string value here
            AppCenter.start(application, "<APP SECRET HERE>",
                    Analytics::class.java, Crashes::class.java, Distribute::class.java)
        }
        if (BuildConfig.DEBUG) {
            AppCenter.setLogLevel(Log.VERBOSE)
        }

        // UI Elements
        mSectionsPagerAdapter = SectionsPagerAdapter(supportFragmentManager)
        mViewPager = findViewById(R.id.container)
        mViewPager?.adapter = mSectionsPagerAdapter
    }

    inner class SectionsPagerAdapter(fm: FragmentManager?) : FragmentPagerAdapter(fm!!,
            BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getItem(@IntRange(from = 0, to = 6) position: Int): Fragment {
            return views[position]
        }

        override fun getCount(): Int {
            return views.size
        }

        override fun getPageTitle(@IntRange(from = 0, to = 6) position: Int): CharSequence {
            when (views[position]) {
                is WelcomeActivity -> return "Welcome"
                is BuildActivity -> return "Build"
                is TestActivity -> return "Test"
                is DistributeActivity -> return "Distribute"
                is CrashesActivity -> return "Crashes"
                is AnalyticsActivity -> return "Analytics"
                is PushActivity -> return "Push"
            }
            return views[position].javaClass.simpleName.trim { it <= ' ' }
                    .replace("Activity", "")
        }
    }
}